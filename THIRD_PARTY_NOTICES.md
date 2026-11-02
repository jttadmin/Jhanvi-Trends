# Third-party notices

Jhanvi Trends' own code is © 2026 Bhumaji Systems, MIT-licensed (see `LICENSE`). It uses the third-party software below,
each under its own license. Full license texts ship inside each package or image.
Last reviewed: October 2026 (Node and Python dependency trees scanned with license-checker / pip-licenses).

## Libraries built into the service images

| Service | Main libraries | Licenses |
|---|---|---|
| frontend, user-service, payment-service (Node.js) | express, pg, bcryptjs, jsonwebtoken, pino, prom-client, OpenTelemetry JS (~260 packages incl. transitive) | MIT, Apache-2.0, ISC, BSD-3-Clause, BlueOak-1.0.0 — all permissive |
| catalog-service, notification-service, loadgen (Python) | FastAPI, Uvicorn, PyMongo, kafka-python, prometheus-client, OpenTelemetry Python, Locust | MIT, Apache-2.0, BSD, PSF-2.0, ZPL-2.1; MPL-2.0 for `certifi` (used unmodified) |
| cart-service, inventory-service (Go) | go-redis, pgx, otelpgx, prometheus/client_golang, OpenTelemetry Go | BSD-2-Clause, MIT, Apache-2.0 |
| order-service (Java) | Spring Boot / Spring Framework / Spring Kafka, kafka-clients, Jackson, Micrometer, PostgreSQL JDBC | Apache-2.0, BSD-2-Clause |
| order-service (Java) | Hibernate ORM 6.x, Logback | LGPL-2.1 (Logback: EPL-1.0 / LGPL-2.1) — used as unmodified libraries |
| order-service (Java) | OpenTelemetry Java agent | Apache-2.0 |

## Container base images

| Image | License |
|---|---|
| node:22-alpine, python:3.13-slim, golang (build only), maven (build only) | MIT / PSF / BSD-3 / Apache-2.0, plus the OS packages' own licenses |
| gcr.io/distroless/static-debian12 | Apache-2.0 (+ Debian package licenses) |
| eclipse-temurin:21-jre | GPL-2.0 with Classpath Exception (lets you run any application on it) |

## Servers and platform components (run unmodified, not redistributed by this repo)

| Component | License | Notes |
|---|---|---|
| PostgreSQL | PostgreSQL License | permissive |
| Valkey | BSD-3-Clause | used instead of Redis 8 (RSALv2 / SSPLv1 / AGPLv3) |
| Apache Kafka (apache/kafka image) | Apache-2.0 | |
| MongoDB Community Server | SSPL-1.0 | Fine for running your own app. Offering MongoDB itself as a service to others triggers SSPL obligations. Alternative: FerretDB (Apache-2.0) |
| Prometheus, Alertmanager, node-exporter, kube-state-metrics, Prometheus Operator | Apache-2.0 | |
| Grafana, Loki, Tempo | AGPL-3.0 | No obligations when run unmodified; modifying them and serving them to others requires sharing those modifications |
| OpenTelemetry Collector (contrib) | Apache-2.0 | |
| Traefik | MIT | |
| Argo CD | Apache-2.0 | |
| metrics-server, local-path-provisioner | Apache-2.0 | |
| postgres_exporter, mongodb_exporter (Percona), kafka_exporter | Apache-2.0 | |
| redis_exporter | MIT | |

## Content

- Product names, brands, book titles, descriptions and the Jhanvi Trends logo were written for this project and are fictional.
- Photos of the seeded demo products (`services/catalog-service/app/photos/`) are free photos from Unsplash under the
  [Unsplash License](https://unsplash.com/license): free for commercial and non-commercial use, no permission or
  attribution needed. Paid Unsplash+ photos are excluded. They were reviewed to avoid visible brand logos; each photo
  only illustrates the product type, and every source is listed in `photos/CREDITS.md`.
- Products without a photo (e.g. ones added in the admin panel) fall back to an SVG generated at runtime by
  catalog-service; the emoji in it are drawn by the viewer's own system font, so no emoji artwork is bundled.
- No web fonts or icon packs are included.
