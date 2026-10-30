FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
# opentelemetry-bootstrap installs the instrumentations for the libraries above (FastAPI, pymongo, ...)
RUN pip install --no-cache-dir -r requirements.txt && opentelemetry-bootstrap -a install
COPY app ./app
RUN useradd -u 10001 -m app
USER 10001
EXPOSE 8080
CMD ["opentelemetry-instrument", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
