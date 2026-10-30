// cart-service: per-user shopping carts stored in Redis hashes
// (key cart:<userId>, field productId, value quantity).
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/extra/redisotel/v9"
	"github.com/redis/go-redis/v9"
)

const cartTTL = 30 * 24 * time.Hour

var cartOps = promauto.NewCounterVec(prometheus.CounterOpts{
	Name: "cart_operations_total", Help: "Cart operations by type",
}, []string{"operation"})

type item struct {
	ProductID int `json:"productId"`
	Quantity  int `json:"quantity"`
}

type server struct {
	rdb *redis.Client
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

func key(r *http.Request) string { return "cart:" + r.PathValue("userId") }

func (s *server) getCart(w http.ResponseWriter, r *http.Request) {
	fields, err := s.rdb.HGetAll(r.Context(), key(r)).Result()
	if err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "cart store unavailable", err)
		return
	}
	items := make([]item, 0, len(fields))
	for pid, qty := range fields {
		p, _ := strconv.Atoi(pid)
		q, _ := strconv.Atoi(qty)
		if p > 0 && q > 0 {
			items = append(items, item{ProductID: p, Quantity: q})
		}
	}
	cartOps.WithLabelValues("get").Inc()
	writeJSON(w, http.StatusOK, map[string]any{"userId": r.PathValue("userId"), "items": items})
}

func (s *server) addItem(w http.ResponseWriter, r *http.Request) {
	var in item
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.ProductID <= 0 || in.Quantity <= 0 || in.Quantity > 50 {
		s.fail(w, r, http.StatusBadRequest, "productId and a quantity between 1 and 50 are required", nil)
		return
	}
	ctx := r.Context()
	pipe := s.rdb.TxPipeline()
	incr := pipe.HIncrBy(ctx, key(r), strconv.Itoa(in.ProductID), int64(in.Quantity))
	pipe.Expire(ctx, key(r), cartTTL)
	if _, err := pipe.Exec(ctx); err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "cart store unavailable", err)
		return
	}
	cartOps.WithLabelValues("add").Inc()
	s.log.InfoContext(ctx, "item added to cart", "userId", r.PathValue("userId"), "productId", in.ProductID, "quantity", incr.Val())
	writeJSON(w, http.StatusCreated, item{ProductID: in.ProductID, Quantity: int(incr.Val())})
}

func (s *server) setItem(w http.ResponseWriter, r *http.Request) {
	pid, err := strconv.Atoi(r.PathValue("productId"))
	var in struct {
		Quantity int `json:"quantity"`
	}
	if err != nil || json.NewDecoder(r.Body).Decode(&in) != nil || in.Quantity < 0 || in.Quantity > 50 {
		s.fail(w, r, http.StatusBadRequest, "a quantity between 0 and 50 is required", nil)
		return
	}
	ctx := r.Context()
	if in.Quantity == 0 {
		err = s.rdb.HDel(ctx, key(r), strconv.Itoa(pid)).Err()
	} else {
		err = s.rdb.HSet(ctx, key(r), strconv.Itoa(pid), in.Quantity).Err()
	}
	if err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "cart store unavailable", err)
		return
	}
	cartOps.WithLabelValues("update").Inc()
	writeJSON(w, http.StatusOK, item{ProductID: pid, Quantity: in.Quantity})
}

func (s *server) removeItem(w http.ResponseWriter, r *http.Request) {
	if err := s.rdb.HDel(r.Context(), key(r), r.PathValue("productId")).Err(); err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "cart store unavailable", err)
		return
	}
	cartOps.WithLabelValues("remove").Inc()
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) clearCart(w http.ResponseWriter, r *http.Request) {
	if err := s.rdb.Del(r.Context(), key(r)).Err(); err != nil {
		s.fail(w, r, http.StatusServiceUnavailable, "cart store unavailable", err)
		return
	}
	cartOps.WithLabelValues("clear").Inc()
	w.WriteHeader(http.StatusNoContent)
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	name := serviceName("cart-service")
	log := newLogger(name)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	shutdownTracing, err := initTracing(ctx)
	if err != nil {
		log.Warn("tracing disabled", "error", err.Error())
	} else {
		defer shutdownTracing(context.Background())
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:     getenv("REDIS_ADDR", "localhost:6379"),
		Password: os.Getenv("REDIS_PASSWORD"),
	})
	if err := redisotel.InstrumentTracing(rdb); err != nil {
		log.Warn("redis tracing disabled", "error", err.Error())
	}
	s := &server{rdb: rdb, log: log}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /cart/{userId}", s.getCart)
	mux.HandleFunc("POST /cart/{userId}/items", s.addItem)
	mux.HandleFunc("PUT /cart/{userId}/items/{productId}", s.setItem)
	mux.HandleFunc("DELETE /cart/{userId}/items/{productId}", s.removeItem)
	mux.HandleFunc("DELETE /cart/{userId}", s.clearCart)
	mux.Handle("GET /metrics", promhttp.Handler())
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := rdb.Ping(r.Context()).Err(); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not ready", "error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ready"})
	})

	srv := &http.Server{Addr: ":" + getenv("PORT", "8080"), Handler: instrument(mux, name, log), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		log.Info("cart-service listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("server failed", "error", err.Error())
			os.Exit(1)
		}
	}()
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
	_ = rdb.Close()
}
