# syntax=docker/dockerfile:1.7

# ---------- Build stage ----------
# Maven and the JDK stay in this stage; only the runnable jar reaches the runtime image.
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# Cache dependency resolution separately from source changes.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/accounting-backend-*.jar /build/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy AS runtime

ARG APP_UID=10001
ARG APP_GID=10001

# curl is used by the container health check. Tesseract is the existing local
# OCR engine used by the application; no OCR data or documents are baked in.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl \
        tesseract-ocr \
        tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid "${APP_GID}" app \
    && useradd --system --uid "${APP_UID}" --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /data/documents \
    && chown -R app:app /app /data/documents

WORKDIR /app

COPY --from=build --chown=app:app /build/app.jar /app/app.jar

USER app:app

EXPOSE 8080

# Container-aware memory sizing. Override JAVA_OPTS at runtime if required.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

# exec keeps the JVM as PID 1 so SIGTERM triggers Spring's graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
