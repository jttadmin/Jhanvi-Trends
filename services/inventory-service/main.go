// inventory-service: stock levels and order reservations in Postgres.
// Reservations are all-or-nothing: one short item fails the whole order.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/exaring/otelpgx"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	reservations = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "inventory_reservations_total", Help: "Reservation attempts by result",
	}, []string{"result"})
	lowStock = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "inventory_low_stock_products", Help: "Products with fewer than 10 units available",
	})
)

var errOutOfStock = errors.New("out of stock")

type line struct {
	ProductID int `json:"productId"`
	Quantity  int `json:"quantity"`
}

type server struct {
	db  *pgxpool.Pool
	log *slog.Logger
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *server) fail(w http.ResponseWriter, r *http.Request, status int, msg string, err error) {
	if err != nil {
		s.log.ErrorContext(r.Context(), msg, "error", err.Error())
	}
	writeJSON(w, status, map[string]string{"error": msg})
}

func (s *server) getStock(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.Atoi(r.PathValue("productId"))
	if err != nil {
		s.fail(w, r, http.StatusBadRequest, "invalid product id", nil)
		return
	}
	var available int
	err = s.db.QueryRow(r.Context(), "SELECT available FROM stock WHERE product_id = $1", id).Scan(&available)
	if errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, r, http.StatusNotFound, "no stock record for product", nil)
		return
	} else if err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "database unavailable", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"productId": id, "available": available})
}

func (s *server) listStock(w http.ResponseWriter, r *http.Request) {
	var ids []int
	for _, part := range strings.Split(r.URL.Query().Get("ids"), ",") {
		if id, err := strconv.Atoi(strings.TrimSpace(part)); err == nil {
			ids = append(ids, id)
		}
	}
	rows, err := s.db.Query(r.Context(),
		"SELECT product_id, available FROM stock WHERE coalesce(cardinality($1::int[]), 0) = 0 OR product_id = ANY($1) ORDER BY product_id", ids)
	if err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "database unavailable", err)
		return
	}
	defer rows.Close()
	out := []map[string]int{}
	for rows.Next() {
		var id, available int
		if err := rows.Scan(&id, &available); err == nil {
			out = append(out, map[string]int{"productId": id, "available": available})
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *server) reserve(w http.ResponseWriter, r *http.Request) {
	var in struct {
		OrderRef string `json:"orderRef"`
		Items    []line `json:"items"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.OrderRef == "" || len(in.Items) == 0 {
		s.fail(w, r, http.StatusBadRequest, "orderRef and items are required", nil)
		return
	}
	ctx := r.Context()
	err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		for _, it := range in.Items {
			tag, err := tx.Exec(ctx,
				"UPDATE stock SET available = available - $2, updated_at = now() WHERE product_id = $1 AND available >= $2",
				it.ProductID, it.Quantity)
			if err != nil {
				return err
			}
			if tag.RowsAffected() == 0 {
				return fmt.Errorf("%w: product %d", errOutOfStock, it.ProductID)
			}
			if _, err := tx.Exec(ctx,
				"INSERT INTO reservations (order_ref, product_id, quantity, status) VALUES ($1, $2, $3, 'RESERVED')",
				in.OrderRef, it.ProductID, it.Quantity); err != nil {
				return err
			}
		}
		return nil
	})
	switch {
	case errors.Is(err, errOutOfStock):
		reservations.WithLabelValues("out_of_stock").Inc()
		s.log.WarnContext(ctx, "reservation rejected", "orderRef", in.OrderRef, "reason", err.Error())
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
	case err != nil:
		reservations.WithLabelValues("error").Inc()
		s.fail(w, r, http.StatusServiceUnavailable, "reservation failed", err)
	default:
		reservations.WithLabelValues("reserved").Inc()
		s.log.InfoContext(ctx, "stock reserved", "orderRef", in.OrderRef, "lines", len(in.Items))
		writeJSON(w, http.StatusCreated, map[string]string{"orderRef": in.OrderRef, "status": "RESERVED"})
	}
}

// settle moves a reservation to COMMITTED, or to RELEASED and puts the stock back.
func (s *server) settle(release bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ref, ctx := r.PathValue("orderRef"), r.Context()
		status := "COMMITTED"
		if release {
			status = "RELEASED"
		}
		err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
			if release {
				if _, err := tx.Exec(ctx, `UPDATE stock s SET available = s.available + r.quantity, updated_at = now()
					FROM reservations r WHERE r.order_ref = $1 AND r.status = 'RESERVED' AND r.product_id = s.product_id`, ref); err != nil {
					return err
				}
			}
			_, err := tx.Exec(ctx, "UPDATE reservations SET status = $2 WHERE order_ref = $1 AND status = 'RESERVED'", ref, status)
			return err
		})
		if err != nil {
			s.fail(w, r, http.StatusServiceUnavailable, "could not update reservation", err)
			return
		}
		reservations.WithLabelValues(strings.ToLower(status)).Inc()
		s.log.InfoContext(ctx, "reservation settled", "orderRef", ref, "status", status)
		writeJSON(w, http.StatusOK, map[string]string{"orderRef": ref, "status": status})
	}
}

func migrate(ctx context.Context, db *pgxpool.Pool, log *slog.Logger) {
	for attempt := 1; ; attempt++ {
		_, err := db.Exec(ctx, `
			CREATE TABLE IF NOT EXISTS stock (
				product_id INT PRIMARY KEY, available INT NOT NULL CHECK (available >= 0),
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
			CREATE TABLE IF NOT EXISTS reservations (
				id BIGSERIAL PRIMARY KEY, order_ref TEXT NOT NULL, product_id INT NOT NULL,
				quantity INT NOT NULL, status TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
			CREATE INDEX IF NOT EXISTS reservations_order_ref ON reservations (order_ref);`)
		if err == nil {
			// Seed stock for the 24 catalog products (fixed IDs).
			for id := 1; id <= 24; id++ {
				_, _ = db.Exec(ctx, "INSERT INTO stock (product_id, available) VALUES ($1, $2) ON CONFLICT DO NOTHING", id, 25+rand.IntN(200))
			}
			log.Info("database ready")
			return
		}
		log.Warn("database not ready, retrying", "attempt", attempt, "error", err.Error())
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Duration(min(attempt, 10)) * time.Second):
		}
	}
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	name := serviceName("inventory-service")
	log := newLogger(name)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if shutdown, err := initTracing(ctx); err != nil {
		log.Warn("tracing disabled", "error", err.Error())
	} else {
		defer shutdown(context.Background())
	}

	cfg, err := pgxpool.ParseConfig(fmt.Sprintf("host=%s port=%s dbname=%s user=%s password=%s pool_max_conns=10",
		getenv("PGHOST", "localhost"), getenv("PGPORT", "5432"), getenv("PGDATABASE", "inventory"),
		getenv("PGUSER", "jhanvitrends"), getenv("PGPASSWORD", "jhanvitrends")))
	if err != nil {
		log.Error("bad database config", "error", err.Error())
		os.Exit(1)
	}
	cfg.ConnConfig.Tracer = otelpgx.NewTracer()
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		log.Error("database pool", "error", err.Error())
		os.Exit(1)
	}
	defer db.Close()
	migrate(ctx, db, log)

	// Keep the low-stock gauge fresh for alerting.
	go func() {
		for {
			var n int
			if err := db.QueryRow(ctx, "SELECT count(*) FROM stock WHERE available < 10").Scan(&n); err == nil {
				lowStock.Set(float64(n))
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(30 * time.Second):
			}
		}
	}()

	s := &server{db: db, log: log}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /stock", s.listStock)
	mux.HandleFunc("GET /stock/{productId}", s.getStock)
	mux.HandleFunc("POST /reservations", s.reserve)
	mux.HandleFunc("POST /reservations/{orderRef}/commit", s.settle(false))
	mux.HandleFunc("POST /reservations/{orderRef}/release", s.settle(true))
	mux.Handle("GET /metrics", promhttp.Handler())
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := db.Ping(r.Context()); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not ready", "error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ready"})
	})

	srv := &http.Server{Addr: ":" + getenv("PORT", "8080"), Handler: instrument(mux, name, log), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		log.Info("inventory-service listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("server failed", "error", err.Error())
			os.Exit(1)
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}
