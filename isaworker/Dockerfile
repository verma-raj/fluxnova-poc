# syntax=docker/dockerfile:1

# ---- build -----------------------------------------------------------------------------------------------
# Tests are skipped here on purpose (the integration tests start servers on random ports); run `mvn verify` in CI before
# building the image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home isaworker \
 && mkdir /config
WORKDIR /app
COPY --from=build /build/target/isaworker-1.0.0.jar /app/app.jar
USER 10001

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The tenant list is NOT part of the image. Mount the folder that holds fluxnova-engine.yml at /config:
#   docker run -v C:\Users\rajivnayan.verma\tenant-endpoint:/config:ro ...
# The application reads /config/fluxnova-engine.yml at start-up and WATCHES it: when the endpoints in it change it ends itself
# (exit code 75) and the container's restart policy (restart: unless-stopped) starts it again, which reads the file afresh.
# Run the container WITH a restart policy, or set ISAWORKER_RESTART_ON_CHANGE=false. It does not onboard or offboard
# tenants, the Fluxnova container does. The blank copy packaged in the jar is the fallback. Mount the FOLDER, not the
# single file, so that a file replaced by an editor is the one that is read and a missing file is just idle mode.
ENV FLUXNOVA_WORKERS_CONFIG=/config/fluxnova-engine.yml
VOLUME ["/config"]

# Credentials for the engines' REST API (set at run time, never baked in):
#   FLUXNOVA_REST_USER, FLUXNOVA_REST_PASSWORD

# /actuator/health only; the workers themselves open no port, they poll the engines.
EXPOSE 8080

# Healthy = the application answers /actuator/health with status UP (the JRE image has no curl). Idle mode is UP.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
