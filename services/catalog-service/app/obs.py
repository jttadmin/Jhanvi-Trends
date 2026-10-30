"""Observability helpers: JSON logs carrying trace_id/span_id, and RED metrics.

Traces are produced by OpenTelemetry auto-instrumentation (the container runs
under `opentelemetry-instrument`), which also instruments FastAPI and pymongo.
"""
import json
import logging
import os
import sys
import time

from opentelemetry import trace
from prometheus_client import Histogram

SERVICE = os.getenv("OTEL_SERVICE_NAME", "catalog-service")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "time": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname.lower(),
            "service": SERVICE,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        ctx = trace.get_current_span().get_span_context()
        if ctx.is_valid:
            entry["trace_id"] = format(ctx.trace_id, "032x")
            entry["span_id"] = format(ctx.span_id, "016x")
        for key, value in getattr(record, "extra_fields", {}).items():
            entry[key] = value
        if record.exc_info:
            entry["error"] = self.formatException(record.exc_info)
        return json.dumps(entry, default=str)


def setup_logging() -> logging.Logger:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
    logging.getLogger("uvicorn.access").disabled = True  # we log requests ourselves
    return logging.getLogger(SERVICE)


def log(logger: logging.Logger, level: int, msg: str, **fields) -> None:
    logger.log(level, msg, extra={"extra_fields": fields})


HTTP_DURATION = Histogram(
    "http_server_requests_seconds",
    "HTTP server request duration in seconds",
    ["method", "uri", "status"],
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
)

SKIP = {"/metrics", "/healthz", "/readyz"}


def metrics_middleware(logger: logging.Logger):
    async def middleware(request, call_next):
        if request.url.path in SKIP:
            return await call_next(request)
        start = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            route = request.scope.get("route")
            uri = getattr(route, "path", "UNMATCHED")
            HTTP_DURATION.labels(request.method, uri, str(status)).observe(time.perf_counter() - start)
            log(logger, logging.ERROR if status >= 500 else logging.INFO, "request completed",
                method=request.method, uri=uri, status=status)

    return middleware
