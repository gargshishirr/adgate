# syntax=docker/dockerfile:1
# Builds one AdGate service image. Pick the service with --build-arg MODULE=<module>:
#   eligibility-service | spend-aggregator | traffic-sim
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -q -pl "${MODULE}" -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
RUN useradd --system --uid 10001 adgate
COPY --from=build /src/${MODULE}/target/${MODULE}.jar /app/app.jar
USER adgate
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
