package com.example.isaworker.worker;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * One-off, non-blocking connectivity check of a tenant's engine, run when its worker starts. It only <em>logs</em> what
 * it finds, so an operator immediately sees a wrong URL, wrong credentials or an engine that is not up yet, instead of
 * having to read the client's polling errors. It never throws and never stops a worker: the polling clients retry on
 * their own with back-off, so an engine that comes up later is picked up without a restart.
 */
@Slf4j
class EngineProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Asks {@code <baseUrl>/version} and logs the outcome. The returned future never completes exceptionally. */
    CompletableFuture<Void> check(String tenantId, String baseUrl, String username, String password) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/version")).timeout(TIMEOUT).GET();
            if (username != null && !username.isBlank()) {
                String credentials = username + ":" + (password == null ? "" : password);
                request.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> {
                        report(tenantId, baseUrl, response, error);
                        return (Void) null;
                    });
        } catch (RuntimeException e) {
            log.warn("[{}] Engine check could not be started for {}: {}", tenantId, baseUrl, e.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void report(String tenantId, String baseUrl, HttpResponse<String> response, Throwable error) {
        try {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                log.warn("[{}] Fluxnova engine at {} is not reachable yet ({}). The worker keeps polling with back-off and "
                        + "starts working as soon as the engine answers.", tenantId, baseUrl, cause.toString());
            } else if (response.statusCode() == 200) {
                log.info("[{}] Fluxnova engine at {} is reachable: {}", tenantId, baseUrl, response.body().strip());
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("[{}] Fluxnova engine at {} rejected the credentials (HTTP {}). Set FLUXNOVA_REST_USER / "
                        + "FLUXNOVA_REST_PASSWORD, or username / password for this tenant. No task can be fetched until "
                        + "this is fixed.", tenantId, baseUrl, response.statusCode());
            } else if (response.statusCode() == 404) {
                log.warn("[{}] Nothing answers at {} (HTTP 404). Check the tenant name in the URL: "
                        + "http://<host>:8080/engine-rest/engine/<tenant>.", tenantId, baseUrl);
            } else {
                log.warn("[{}] Fluxnova engine at {} answered HTTP {}", tenantId, baseUrl, response.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("[{}] Engine check result could not be reported: {}", tenantId, e.toString());
        }
    }
}
