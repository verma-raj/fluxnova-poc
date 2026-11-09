# syntax=docker/dockerfile:1

# ---- build -----------------------------------------------------------------------------------------------
# Tests are skipped here on purpose: the integration test starts a PostgreSQL server, which refuses to run as
# root inside a container. Run `mvn verify` in CI before building the image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home fluxnova
WORKDIR /app
COPY --from=build /build/target/fluxnova-multitenant-1.0.0.jar /app/app.jar
USER 10001

# Size the heap from the Fargate task memory. Every tenant engine keeps its own caches and thread pools in the
# heap, so size the task for (number of tenants) x (per-engine footprint), measured with your own models.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Mount the folder that holds tenants.yml here (read-only is enough). Mount the FOLDER, not the single file:
# editors save by replacing the file, and a single-file bind mount would keep pointing at the old one.
ENV FLUXNOVA_CONFIG_PATH=/configs/tenants.yml
# Deploy-and-run folder: mount a host folder here, WRITABLE (not ":ro"): the application creates a sub-folder named after
# each tenant when the tenant is onboarded (/config/bpmn_deployment/<tenant id>, visible on the host at once). A .bpmn
# file that lands in a tenant's sub-folder is deployed to that tenant's engine only, and started. Files that are already
# there when the tenant's engine starts are deployed but not started. If nothing is mounted the folder is simply reported
# as missing and the rest of the application is unaffected. The container runs as uid 10001: on Linux hosts the mounted
# folder must be writable for that user.
ENV FLUXNOVA_BPMN_DEPLOYMENT_DIR=/config/bpmn_deployment

# Web portal (/fluxnova), REST API (/engine-rest), management API (/api/tenants) and /actuator/health.
EXPOSE 8080

# Healthy = the application answers /actuator/health with status UP (the JRE image has no curl).
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
