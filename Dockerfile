# syntax=docker/dockerfile:1
# Builds one AdGate service image. Pick the service with --build-arg MODULE=<module>:
#   eligibility-service | spend-aggregator | traffic-sim
# Optional: --build-arg HEALTH_PORT=<port> adds a health check on http://127.0.0.1:<port>/readyz.
# Base images are pinned to exact versions; update them on purpose (Dependabot opens PRs).
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -q -pl "${MODULE}" -am package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
ARG MODULE
ARG HEALTH_PORT=""
WORKDIR /app
RUN groupadd --system --gid 10001 adgate \
    && useradd --system --uid 10001 --gid adgate --no-create-home --shell /usr/sbin/nologin adgate
# Only the jar is copied: no sources, build cache or secrets end up in this image. It stays owned by root (read-only).
COPY --from=build /src/${MODULE}/target/${MODULE}.jar /app/app.jar
# Inside a container the service must listen on all interfaces; Compose publishes ports on 127.0.0.1 only.
ENV SERVER_ADDRESS=0.0.0.0 \
    HEALTH_PORT=${HEALTH_PORT}
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD test -z "$HEALTH_PORT" || curl -fsS -o /dev/null "http://127.0.0.1:${HEALTH_PORT}/readyz" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
