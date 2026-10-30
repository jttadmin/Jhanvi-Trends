#!/usr/bin/env python3
"""Generates deploy/charts/jhanvi-trends/dashboards/jhanvi-trends-overview.json.

Edit panels here and re-run rather than hand-editing the JSON.
"""
import json
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
NS = 'namespace="$namespace"'
panels, y = [], 0
pid = iter(range(1, 1000))


def row(title):
    global y
    panels.append({"type": "row", "id": next(pid), "title": title, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}})
    y += 1


def stat(title, expr, x, unit="short", w=6, decimals=None, thresholds=None):
    p = {"type": "stat", "id": next(pid), "title": title, "datasource": PROM,
         "gridPos": {"h": 4, "w": w, "x": x, "y": y},
         "targets": [{"refId": "A", "expr": expr, "datasource": PROM}],
         "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"},
         "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                                      "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "blue", "value": None}]}},
                         "overrides": []}}
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    panels.append(p)


def ts(title, targets, x, w=12, h=8, unit="short", stack=False):
    panels.append({"type": "timeseries", "id": next(pid), "title": title, "datasource": PROM,
                   "gridPos": {"h": h, "w": w, "x": x, "y": y},
                   "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l, "datasource": PROM}
                               for i, (e, l) in enumerate(targets)],
                   "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["mean", "max"]},
                               "tooltip": {"mode": "multi", "sort": "desc"}},
                   "fieldConfig": {"defaults": {"unit": unit, "custom": {"fillOpacity": 12, "lineWidth": 2,
                                   "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []}})


row("Business")
stat("Orders confirmed (1h)", f"sum(increase(orders_placed_total{{{NS}}}[1h]))", 0, decimals=0)
stat("Revenue (1h)", f"sum(increase(orders_value_inr_sum{{{NS}}}[1h]))", 6, unit="currencyINR", decimals=0)
stat("Checkout success (1h)",
     f'sum(increase(checkouts_total{{{NS},result="success"}}[1h])) / clamp_min(sum(increase(checkouts_total{{{NS}}}[1h])), 1)',
     12, unit="percentunit", decimals=1,
     thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 0.85}, {"color": "green", "value": 0.95}])
stat("Payment declines (15m)",
     f'sum(rate(payments_total{{{NS},status="declined"}}[15m])) / clamp_min(sum(rate(payments_total{{{NS}}}[15m])), 1e-9)',
     18, unit="percentunit", decimals=1,
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.1}, {"color": "red", "value": 0.2}])
y += 4
ts("Checkouts by result", [(f"sum by (result) (rate(checkouts_total{{{NS}}}[5m])) * 60", "{{result}}")], 0, unit="short", stack=True)
ts("Orders failed by reason (per min)", [(f"sum by (reason) (rate(orders_failed_total{{{NS}}}[5m])) * 60", "{{reason}}"),
                                         (f"sum(rate(orders_placed_total{{{NS}}}[5m])) * 60", "confirmed")], 12)
y += 8

row("Golden signals (RED) per service")
ts("Requests / sec", [(f"sum by (job) (rate(http_server_requests_seconds_count{{{NS}}}[$__rate_interval]))", "{{job}}")], 0, unit="reqps")
ts("Error ratio (5xx)", [(f'sum by (job) (rate(http_server_requests_seconds_count{{{NS},status=~"5.."}}[$__rate_interval])) / '
                          f"clamp_min(sum by (job) (rate(http_server_requests_seconds_count{{{NS}}}[$__rate_interval])), 1e-9)", "{{job}}")],
   12, unit="percentunit")
y += 8
ts("p95 latency", [(f"histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_seconds_bucket{{{NS}}}[$__rate_interval])))", "{{job}}")], 0, unit="s")
ts("Slowest endpoints (p95)", [(f"topk(8, histogram_quantile(0.95, sum by (job, uri, le) (rate(http_server_requests_seconds_bucket{{{NS}}}[5m]))))",
                                "{{job}} {{uri}}")], 12, unit="s")
y += 8

row("Domain services")
ts("Payments", [(f"sum by (status) (rate(payments_total{{{NS}}}[5m])) * 60", "{{status}}")], 0, w=8, unit="short")
ts("Inventory reservations", [(f"sum by (result) (rate(inventory_reservations_total{{{NS}}}[5m])) * 60", "{{result}}")], 8, w=8)
ts("Cart operations", [(f"sum by (operation) (rate(cart_operations_total{{{NS}}}[5m])) * 60", "{{operation}}")], 16, w=8)
y += 8
ts("Notifications sent", [(f"sum by (template) (rate(notifications_sent_total{{{NS}}}[5m])) * 60", "{{template}}")], 0, w=8)
ts("Searches", [(f"sum by (has_results) (rate(catalog_searches_total{{{NS}}}[5m])) * 60", "results={{has_results}}")], 8, w=8)
ts("Logins / signups", [(f"sum by (result) (rate(user_logins_total{{{NS}}}[5m])) * 60", "login {{result}}"),
                        (f"sum(rate(user_signups_total{{{NS}}}[5m])) * 60", "signups")], 16, w=8)
y += 8

row("Datastores")
ts("Kafka consumer lag (order-events)", [(f"sum by (consumergroup) (kafka_consumergroup_lag{{{NS},topic=\"order-events\"}})", "{{consumergroup}}")], 0, w=8)
ts("Kafka messages in / sec", [(f"sum by (topic) (rate(kafka_topic_partition_current_offset{{{NS},topic!~\"__.*\"}}[5m]))", "{{topic}}")], 8, w=8, unit="short")
ts("Postgres connections", [(f"sum by (datname) (pg_stat_database_numbackends{{{NS},datname!~\"template.*\"}})", "{{datname}}")], 16, w=8)
y += 8
ts("MongoDB operations / sec", [(f"sum by (type) (rate(mongodb_op_counters_total{{{NS}}}[5m]))", "{{type}}")], 0, w=8, unit="ops")
ts("MongoDB connections", [(f"sum(mongodb_connections{{{NS},state=\"current\"}})", "current")], 8, w=8)
ts("Redis memory", [(f"sum(redis_memory_used_bytes{{{NS}}})", "memory")], 16, w=8, unit="bytes")
y += 8

row("Resources")
ts("CPU by service (cores)", [(f'sum by (container) (rate(container_cpu_usage_seconds_total{{{NS},container!="",container!="POD"}}[5m]))', "{{container}}")], 0)
ts("Memory by service", [(f'sum by (container) (container_memory_working_set_bytes{{{NS},container!="",container!="POD"}})', "{{container}}")], 12, unit="bytes")
y += 8

row("Logs")
panels.append({"type": "logs", "id": next(pid), "title": "Warnings and errors (click a trace_id to open the trace)", "datasource": LOKI,
               "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
               "targets": [{"refId": "A", "datasource": LOKI,
                            "expr": '{k8s_namespace_name="$namespace"} |~ `"level":"(warn|warning|error|WARN|ERROR)"`'}],
               "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True}})

dashboard = {
    "uid": "jhanvi-trends-overview",
    "title": "Jhanvi Trends / Service Overview",
    "tags": ["jhanvi-trends", "red", "business"],
    "timezone": "browser",
    "refresh": "30s",
    "schemaVersion": 39,
    "time": {"from": "now-1h", "to": "now"},
    "links": [{"title": "Service graph (Tempo)", "type": "link", "url": "/explore?left=%7B%22datasource%22:%22tempo%22,%22queries%22:%5B%7B%22refId%22:%22A%22,%22queryType%22:%22serviceMap%22%7D%5D%7D",
               "targetBlank": True}],
    "templating": {"list": [{
        "name": "namespace", "type": "query", "datasource": PROM, "label": "Namespace",
        "query": {"query": "label_values(http_server_requests_seconds_count, namespace)", "refId": "ns"},
        "definition": "label_values(http_server_requests_seconds_count, namespace)",
        "current": {"text": "jhanvi-trends", "value": "jhanvi-trends"}, "refresh": 2}]},
    "panels": panels,
}

out = Path(__file__).resolve().parent.parent / "deploy/charts/jhanvi-trends/dashboards/jhanvi-trends-overview.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
