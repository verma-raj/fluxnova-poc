package com.example.isaworker.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code fluxnova-workers} section of the configuration.
 * <p>
 * {@link #tenants} comes from {@code fluxnova-engine.yml}: the copy packaged in the jar lists none, and the external
 * copy (a mounted volume, {@code /config/fluxnova-engine.yml}) replaces it with the engine endpoints to serve.
 * Everything else has defaults in {@code application.yml} and can be overridden in the external file or through
 * environment variables.
 * <p>
 * This class is bound twice: by Spring Boot at start-up (application.yml, environment, packaged template: the
 * <em>baseline</em>), and by {@link EngineConfigLoader} once, when {@link EngineConfigReader} reads the external file at
 * start-up (baseline + file). The workers are started from that result; to change it, edit the file and restart.
 */
@Data
@ConfigurationProperties(prefix = "fluxnova-workers")
public class TenantWorkerProperties {

    /** One entry per Fluxnova tenant engine this worker serves. Empty (the default) means idle mode. */
    private List<Tenant> tenants = new ArrayList<>();

    /** HTTP Basic user for tenants that do not set their own {@code username}. Blank = send no credentials. */
    private String defaultUsername;

    /** HTTP Basic password for tenants that do not set their own. Never logged. */
    @ToString.Exclude
    private String defaultPassword;

    /** Prefix of the worker id shown in the engine for locked tasks: {@code <prefix>-<tenant>-<host>}. */
    private String workerIdPrefix = "isaworker";

    /** How long a fetched task stays locked to this worker; must exceed the time a handler needs. */
    private Duration lockDuration = Duration.ofSeconds(30);

    /** Long-polling time: how long the engine holds a fetchAndLock request open while there is no task. */
    private Duration asyncResponseTimeout = Duration.ofSeconds(20);

    /** Maximum number of tasks fetched per request. */
    private int maxTasks = 10;

    /**
     * Format of the dates in the engine's JSON answers (lock expiration time, create time). A Fluxnova 3.x engine writes the
     * zone offset with a colon ({@code 2026-11-06T14:46:20.150+00:00}); the client library's own default only reads it
     * without ({@code +0000}) and would then reject EVERY task it fetches. Use {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} for an
     * engine that still writes the old form.
     */
    private String dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    /** First pause after a failed poll (engine down, wrong credentials, ...). */
    private Duration backoffInitial = Duration.ofMillis(500);

    /** Upper bound of the pause after consecutive failed polls. */
    private Duration backoffMax = Duration.ofSeconds(30);

    /** The pause is multiplied by this after every consecutive failed poll. */
    private double backoffFactor = 2.0;

    /** One tenant of the Fluxnova server: its id and the REST endpoint of its own engine. */
    @Data
    public static class Tenant {

        /** Tenant id, as used in the engine URL and in the logs. Must be unique. */
        private String id;

        /**
         * Engine REST endpoint of this tenant, e.g. {@code http://fluxnova-application:8080/engine-rest/engine/tenant-b}.
         * The client appends {@code /external-task/...} to it.
         */
        private String baseUrl;

        /** Overrides {@code fluxnova-workers.default-username} for this tenant. */
        private String username;

        /** Overrides {@code fluxnova-workers.default-password} for this tenant. Never logged. */
        @ToString.Exclude
        private String password;
    }
}
