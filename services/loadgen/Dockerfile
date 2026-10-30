FROM python:3.13-slim
RUN pip install --no-cache-dir locust==2.47.0 && useradd -u 10001 -m app
WORKDIR /app
COPY locustfile.py .
USER 10001
EXPOSE 8089
# Users/spawn rate/host come from env: LOCUST_USERS, LOCUST_SPAWN_RATE, LOCUST_HOST
ENTRYPOINT ["locust", "-f", "/app/locustfile.py", "--headless", "--only-summary"]
