# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY . .

ARG SERVICE_MODULE
ARG JAR_FILE
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
        -pl "${SERVICE_MODULE}" -am package -DskipTests \
    && cp "${JAR_FILE}" /workspace/application.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && groupadd --system application \
    && useradd --system --gid application --home-dir /app --shell /usr/sbin/nologin application \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=application:application /workspace/application.jar application.jar

ENV SERVER_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent "http://localhost:${SERVER_PORT}/actuator/health" > /dev/null || exit 1

USER application
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
