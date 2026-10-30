"""notification-service: consumes order events from Kafka and "sends" emails.

order-service publishes with W3C trace context in the Kafka record headers
(OpenTelemetry Java agent). We extract it here and continue the trace, so a
checkout shows up in Tempo as one trace: frontend -> order -> Kafka -> notification.
"""
import json
import logging
import os
import random
import signal
import sys
import time

from kafka import KafkaConsumer
from kafka.errors import KafkaError
from opentelemetry import propagate, trace
from opentelemetry.trace import SpanKind
from prometheus_client import Counter, Histogram, start_http_server

SERVICE = os.getenv("OTEL_SERVICE_NAME", "notification-service")
TOPIC = os.getenv("ORDER_EVENTS_TOPIC", "order-events")
GROUP = os.getenv("KAFKA_CONSUMER_GROUP", "notification-service")
BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")

tracer = trace.get_tracer(SERVICE)


class JsonFormatter(logging.Formatter):
    def format(self, record):
        entry = {
            "time": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname.lower(),
            "service": SERVICE,
            "msg": record.getMessage(),
            **getattr(record, "extra_fields", {}),
        }
        ctx = trace.get_current_span().get_span_context()
        if ctx.is_valid:
            entry["trace_id"] = format(ctx.trace_id, "032x")
            entry["span_id"] = format(ctx.span_id, "016x")
        return json.dumps(entry, default=str)


handler = logging.StreamHandler(sys.stdout)
handler.setFormatter(JsonFormatter())
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper(), handlers=[handler])
logging.getLogger("kafka").setLevel(logging.WARNING)
logger = logging.getLogger(SERVICE)


def log(level, msg, **fields):
    logger.log(level, msg, extra={"extra_fields": fields})


EVENTS = Counter("notification_events_total", "Order events consumed", ["event", "result"])
SENT = Counter("notifications_sent_total", "Notifications sent", ["channel", "template"])
SEND_TIME = Histogram("notification_send_seconds", "Time spent sending a notification",
                      buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1))


def send_email(to: str, template: str, data: dict) -> None:
    with tracer.start_as_current_span("send_email") as span, SEND_TIME.time():
        span.set_attribute("notification.template", template)
        time.sleep(random.uniform(0.02, 0.12))  # pretend to call an email provider
        SENT.labels("email", template).inc()
        log(logging.INFO, "email sent", to=to, template=template, orderId=data.get("orderId"))


def handle(record) -> None:
    carrier = {k: v.decode() for k, v in (record.headers or []) if v is not None}
    ctx = propagate.extract(carrier)
    with tracer.start_as_current_span(f"{TOPIC} process", context=ctx, kind=SpanKind.CONSUMER) as span:
        span.set_attribute("messaging.system", "kafka")
        span.set_attribute("messaging.destination.name", record.topic)
        span.set_attribute("messaging.kafka.partition", record.partition)
        span.set_attribute("messaging.kafka.offset", record.offset)
        event = "unknown"
        try:
            data = json.loads(record.value)
            event = data.get("type", "unknown")
            if event == "order.placed":
                send_email(data.get("userEmail") or "unknown", "order_confirmation", data)
            elif event == "order.failed":
                send_email(data.get("userEmail") or "unknown", "payment_failed", data)
            EVENTS.labels(event, "ok").inc()
        except Exception as exc:  # noqa: BLE001 - a bad record is logged and skipped
            EVENTS.labels(event, "error").inc()
            span.record_exception(exc)
            log(logging.ERROR, "failed to process event", event=event, error=str(exc), offset=record.offset)


def main():
    start_http_server(int(os.getenv("METRICS_PORT", "8080")))  # /metrics (also used for probes)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    attempt = 0
    while True:
        try:
            consumer = KafkaConsumer(
                TOPIC,
                bootstrap_servers=BOOTSTRAP.split(","),
                group_id=GROUP,
                auto_offset_reset="earliest",
                enable_auto_commit=True,
                client_id=SERVICE,
            )
            attempt = 0
            log(logging.INFO, "consuming", topic=TOPIC, group=GROUP, bootstrap=BOOTSTRAP)
            for record in consumer:
                handle(record)
        except KafkaError as exc:
            attempt += 1
            log(logging.WARNING, "kafka unavailable, retrying", attempt=attempt, error=str(exc))
            time.sleep(min(attempt * 2, 30))


if __name__ == "__main__":
    main()
