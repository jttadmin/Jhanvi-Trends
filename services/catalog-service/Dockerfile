# Stages: base -> test (docker build --target test .) | base -> runtime (default target)
FROM python:3.13-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
# opentelemetry-bootstrap installs the instrumentations for the libraries above (FastAPI, pymongo, ...)
RUN pip install --no-cache-dir -r requirements.txt && opentelemetry-bootstrap -a install
COPY app ./app

# Lint for real errors (syntax, undefined names), prove the app imports with its pinned
# dependencies, then run pytest once a tests/ folder exists. CI runs this before building the image.
FROM base AS test
RUN pip install --no-cache-dir ruff pytest
COPY . .
RUN ruff check --select E9,F63,F7,F82 . && \
    python -c "import app.main" && \
    if [ -d tests ]; then python -m pytest -q tests; else echo "no tests/ yet"; fi

FROM base
RUN useradd -u 10001 -m app
USER 10001
EXPOSE 8080
CMD ["opentelemetry-instrument", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
