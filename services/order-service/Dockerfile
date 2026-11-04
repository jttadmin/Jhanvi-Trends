# Stages: deps -> test (docker build --target test .) | deps -> build -> runtime (default target)
FROM maven:3.9-eclipse-temurin-26 AS deps
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Compiles and runs the unit tests (src/test/java). CI runs this before building the image.
FROM deps AS test
COPY src ./src
RUN mvn -B -ntp verify

FROM deps AS build
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
ARG OTEL_AGENT_VERSION=2.32.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN chmod 444 /otel/opentelemetry-javaagent.jar && useradd -u 10001 -m app
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER 10001
# The agent auto-instruments Spring MVC, RestClient, JDBC/Hikari and Kafka (trace context goes into record headers)
# and puts trace_id/span_id in the logging MDC.
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
