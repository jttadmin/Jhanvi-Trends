package main

// Observability plumbing shared by the Go services: OTLP tracing, JSON logs
// that carry trace_id/span_id, and the common RED histogram.

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

func serviceName(def string) string {
	if v := os.Getenv("OTEL_SERVICE_NAME"); v != "" {
		return v
	}
	return def
}

// initTracing reads the standard OTEL_EXPORTER_OTLP_* variables.
// service.name and other attributes come from OTEL_SERVICE_NAME / OTEL_RESOURCE_ATTRIBUTES.
func initTracing(ctx context.Context) (func(context.Context) error, error) {
	exp, err := otlptracegrpc.New(ctx)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exp), sdktrace.WithResource(resource.Default()))
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	return tp.Shutdown, nil
}

// traceHandler adds trace_id/span_id from the context to every log record.
type traceHandler struct{ slog.Handler }

func (h traceHandler) Handle(ctx context.Context, r slog.Record) error {
	if sc := trace.SpanContextFromContext(ctx); sc.IsValid() {
		r.AddAttrs(slog.String("trace_id", sc.TraceID().String()), slog.String("span_id", sc.SpanID().String()))
	}
	return h.Handler.Handle(ctx, r)
}
func (h traceHandler) WithAttrs(a []slog.Attr) slog.Handler {
	return traceHandler{h.Handler.WithAttrs(a)}
}
func (h traceHandler) WithGroup(n string) slog.Handler { return traceHandler{h.Handler.WithGroup(n)} }

func newLogger(service string) *slog.Logger {
	base := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			switch a.Key {
			case slog.MessageKey:
				a.Key = "msg"
			case slog.LevelKey:
				a.Value = slog.StringValue(map[string]string{"DEBUG": "debug", "INFO": "info", "WARN": "warn", "ERROR": "error"}[a.Value.String()])
			}
			return a
		},
	})
	return slog.New(traceHandler{base}).With("service", service)
}

var httpDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
	Name:    "http_server_requests_seconds",
	Help:    "HTTP server request duration in seconds",
	Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5},
}, []string{"method", "uri", "status"})

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) { s.status = code; s.ResponseWriter.WriteHeader(code) }

// instrument wraps the mux with tracing (otelhttp), RED metrics and access logs.
func instrument(mux *http.ServeMux, service string, log *slog.Logger) http.Handler {
	observed := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/metrics", "/healthz", "/readyz":
			mux.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		mux.ServeHTTP(rec, r)
		_, pattern := mux.Handler(r)
		uri := "UNMATCHED"
		if pattern != "" {
			uri = pattern
			if i := indexSpace(pattern); i >= 0 { // "GET /cart/{userId}" -> "/cart/{userId}"
				uri = pattern[i+1:]
			}
		}
		trace.SpanFromContext(r.Context()).SetName(r.Method + " " + uri)
		httpDuration.WithLabelValues(r.Method, uri, strconv.Itoa(rec.status)).Observe(time.Since(start).Seconds())
		level := slog.LevelInfo
		if rec.status >= 500 {
			level = slog.LevelError
		}
		log.Log(r.Context(), level, "request completed", "method", r.Method, "uri", uri, "status", rec.status)
	})
	return otelhttp.NewHandler(observed, service)
}

func indexSpace(s string) int {
	for i := 0; i < len(s); i++ {
		if s[i] == ' ' {
			return i
		}
	}
	return -1
}
