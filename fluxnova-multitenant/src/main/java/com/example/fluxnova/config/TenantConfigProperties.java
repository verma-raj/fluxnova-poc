package com.example.fluxnova.config;

import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Object model of {@code tenants.yml}. The class tree mirrors the document one-to-one:
 *
 * <pre>
 * fluxnova:
 *   cluster:
 *     host: "shared-postgres.internal"
 *     port: 5432
 *     admin-user: "postgres_admin"
 *     admin-password: "secure_password"
 *   tenants:
 *     - id: "tenant-a"
 *       dbName: "fluxnova_tenant_a"
 * </pre>
 *
 * Keys may be written in kebab-case ({@code admin-user}) or camelCase ({@code adminUser}, {@code dbName});
 * see {@link TenantConfigLoader}. Instances are plain mutable beans because SnakeYAML populates them through
 * their setters; {@link TenantConfigLoader} validates them straight after parsing, so the rest of the
 * application only ever sees validated objects.
 */
@Data
public class TenantConfigProperties {

    private Fluxnova fluxnova;

    /** The {@code fluxnova:} root element. */
    @Data
    public static class Fluxnova {
        private Cluster cluster;
        private List<Tenant> tenants = new ArrayList<>();
    }

    /** The shared PostgreSQL / Amazon RDS cluster that hosts every tenant database. */
    @Data
    public static class Cluster {
        private String host;
        private int port = 5432;
        private String adminUser;
        /** Excluded from toString() so the secret can never end up in a log line. */
        @ToString.Exclude
        private String adminPassword;
        /** Maintenance database the admin connection is opened against to run CREATE DATABASE. */
        private String adminDatabase = "postgres";
    }

    /** One tenant = one engine = one dedicated database. */
    @Data
    public static class Tenant {
        /** Stable identifier; becomes the engine name, the pool name and the REST path segment. */
        private String id;
        /** Name of the dedicated PostgreSQL database, created on first use. */
        private String dbName;
    }
}
