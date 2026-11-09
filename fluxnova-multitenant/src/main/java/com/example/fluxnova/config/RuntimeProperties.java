package com.example.fluxnova.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application-level settings ({@code fluxnova.runtime.*} in application.yml / environment variables).
 * Everything tenant specific lives in {@code tenants.yml} instead, see {@link TenantConfigProperties}.
 */
@Data
@ConfigurationProperties(prefix = "fluxnova.runtime")
public class RuntimeProperties {

    /** Location of tenants.yml as seen by the JVM (inside the container: /configs/tenants.yml). */
    private String configPath = "/configs/tenants.yml";

    /** If not blank, replaces fluxnova.cluster.admin-password from the YAML (e.g. injected from Secrets Manager). */
    @ToString.Exclude
    private String adminPassword;

    /** TCP connect timeout of the admin connection used for CREATE DATABASE. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** How long to wait for the cluster-wide per-database advisory lock taken while creating a database. */
    private Duration lockTimeout = Duration.ofSeconds(60);

    /** First retry delay after a failed onboarding; doubles per consecutive failure. */
    private Duration retryInitialDelay = Duration.ofSeconds(15);

    /** Upper bound of the retry delay. */
    private Duration retryMaxDelay = Duration.ofMinutes(10);

    private final Pool pool = new Pool();
    private final Jobs jobs = new Jobs();
    private final Web web = new Web();
    private final Offboarding offboarding = new Offboarding();
    private final BpmnDeployment bpmnDeployment = new BpmnDeployment();

    /**
     * Deploy-and-run folder: {@code .bpmn} files that land in {@link #directory} are deployed to the tenant engines and
     * started automatically, see {@code BpmnFolderWatcher}. (The scan interval is
     * {@code fluxnova.runtime.bpmn-deployment.poll-interval}, read by the scheduler.)
     */
    @Data
    public static class BpmnDeployment {
        /**
         * Folder to watch, as seen by the JVM (inside the container: /config/bpmn_deployment). Blank switches the feature off.
         * Every running tenant gets its own sub-folder here, named after the tenant and created when the tenant is
         * onboarded; a file in it is deployed to that tenant's engine only. A file directly in this folder belongs to nobody
         * and is ignored.
         */
        private String directory = "";
        /** Start an instance of every deployed process that has a plain start event. Off = deploy only. */
        private boolean autoStart = true;
    }

    /** HikariCP settings applied to every tenant's own pool. */
    @Data
    public static class Pool {
        private int maximumSize = 10;
        private int minimumIdle = 1;
        private Duration connectionTimeout = Duration.ofSeconds(30);
        private Duration idleTimeout = Duration.ofMinutes(10);
        private Duration maxLifetime = Duration.ofMinutes(30);
    }

    /** The administrator that is created in every tenant engine so that the web portal can be used right away. */
    @Data
    public static class Web {
        private String adminUser = "admin";
        /** No default on purpose. Without a password no administrator is created (the Admin app's setup page can be used). */
        @ToString.Exclude
        private String adminPassword;
        private String adminFirstName = "Fluxnova";
        private String adminLastName = "Administrator";
        private String adminEmail = "admin@localhost";
        /**
         * One sign-in for every tenant engine: after an administrator signs in to one engine, the portal session is
         * also signed in to every other engine where the same user id and password are valid and the user is an
         * administrator. Switch off to require a separate sign-in per engine.
         */
        private boolean singleSignOn = true;
    }

    /**
     * What happens when a tenant is removed from tenants.yml. A removed tenant is OFFBOARDED: its engine is stopped, its
     * connection pool is closed and its database is dropped (the data is gone for good).
     */
    @Data
    public static class Offboarding {
        /** Set to false to keep removed tenants' engines running and their databases untouched. */
        private boolean enabled = true;
        /**
         * How long a tenant has to stay absent from the (valid) file before it is offboarded. A saved-by-mistake file
         * can be corrected within this time; putting the tenant back cancels the offboarding.
         */
        private Duration gracePeriod = Duration.ofSeconds(30);
        /**
         * Whether removing EVERY tenant (an empty tenant list) offboards them too, so that the last tenant can be
         * removed like any other. Switch off to refuse that, as protection against emptying the file by accident:
         * engines and databases are then kept until at least one tenant is listed again.
         */
        private boolean allowRemoveAll = true;
        /**
         * Name of the group of tenants this runtime manages in the cluster's tenant registry. Runtimes that share one
         * PostgreSQL cluster but have different tenants.yml files must use different scopes; otherwise each would
         * consider the other's tenants removed and drop their databases. All tasks of ONE service share one scope.
         */
        private String scope = "default";
    }

    /** Job executor settings applied to every tenant's own engine. */
    @Data
    public static class Jobs {
        private boolean activate = true;
        private int corePoolSize = 2;
        private int maxPoolSize = 5;
        private int queueSize = 5;
        private int maxJobsPerAcquisition = 3;
    }
}
