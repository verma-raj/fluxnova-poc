package com.example.fluxnova.db;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.config.TenantConfigProperties.Cluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cluster's memory of which tenant databases this runtime manages.
 * <p>
 * Offboarding must not depend on an engine being loaded in memory: if a tenant is removed from {@code tenants.yml} while
 * the container is down, restarting, or in the middle of the grace period, no engine exists any more and the database
 * would be left behind. This registry, a table in the cluster's maintenance database, survives restarts and is shared by
 * every task of the service, so such a database is still found and offboarded.
 * <p>
 * Rules that keep it safe:
 * <ul>
 *   <li><b>Only registered databases are ever dropped.</b> A database is registered after its tenant's engine has
 *       started. Databases the application did not create or adopt this way are never touched.</li>
 *   <li><b>Scoped.</b> Every row belongs to a scope ({@code fluxnova.runtime.offboarding.scope}). Runtimes with different
 *       configuration files that share one cluster use different scopes and never see each other's tenants.</li>
 *   <li><b>The grace period lives in the database</b> ({@code absent_since}, measured with the database clock), so a
 *       restart does not reset it and tasks with slightly different clocks agree.</li>
 * </ul>
 * The administrator needs the privilege to create a table in the maintenance database ({@code postgres} on RDS: the master
 * user has it). Without it the registry cannot be used; the application logs that and keeps working, but databases of
 * tenants removed while no engine is running are then not cleaned up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRegistryService {

    static final String TABLE = "fluxnova_tenant_registry";
    /** Serialises creation of the table between tasks that start at the same moment. */
    private static final long DDL_LOCK_KEY = 0x464C58_5245_47L;

    private final DatabaseProvisioningService provisioning;
    private final RuntimeProperties runtime;

    /** "host:port/maintenance-db" of clusters whose table is known to exist. */
    private final Set<String> tableEnsured = ConcurrentHashMap.newKeySet();

    /**
     * A registered tenant.
     *
     * @param absentSeconds how long the tenant has been missing from the configuration according to the database clock,
     *                      or {@code null} if it has not been found missing
     */
    public record RegisteredTenant(String tenantId, String dbName, Long absentSeconds) {
    }

    /** Records that the tenant's database is managed by this runtime. Idempotent; also clears a pending absence. */
    public void register(Cluster cluster, String tenantId, String dbName) throws SQLException {
        withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (scope, tenant_id, db_name) VALUES (?, ?, ?) "
                            + "ON CONFLICT (scope, tenant_id) DO UPDATE "
                            + "SET db_name = EXCLUDED.db_name, last_seen_at = now(), absent_since = NULL")) {
                ps.setString(1, scope());
                ps.setString(2, tenantId);
                ps.setString(3, dbName);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Clears the absence of tenants that are listed in the configuration again. */
    public void markPresent(Cluster cluster, Collection<String> configuredTenantIds) throws SQLException {
        if (configuredTenantIds.isEmpty()) {
            return;
        }
        withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET absent_since = NULL, last_seen_at = now() "
                            + "WHERE scope = ? AND tenant_id = ANY (?) AND absent_since IS NOT NULL")) {
                ps.setString(1, scope());
                ps.setArray(2, connection.createArrayOf("text", configuredTenantIds.toArray(new String[0])));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Clears every absence of this scope: the grace period starts over (the configuration file is not trustworthy right now). */
    public void resetAbsences(Cluster cluster) throws SQLException {
        withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET absent_since = NULL WHERE scope = ? AND absent_since IS NOT NULL")) {
                ps.setString(1, scope());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Registered tenants of this scope that are not in {@code configuredTenantIds}. */
    public List<RegisteredTenant> missing(Cluster cluster, Collection<String> configuredTenantIds) throws SQLException {
        return withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT tenant_id, db_name, "
                            + "CASE WHEN absent_since IS NULL THEN NULL ELSE EXTRACT(EPOCH FROM (now() - absent_since))::bigint END "
                            + "FROM " + TABLE + " WHERE scope = ? AND tenant_id <> ALL (?) ORDER BY tenant_id")) {
                ps.setString(1, scope());
                ps.setArray(2, connection.createArrayOf("text", configuredTenantIds.toArray(new String[0])));
                List<RegisteredTenant> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tenantId = rs.getString(1);
                        String dbName = rs.getString(2);
                        long seconds = rs.getLong(3);
                        Long absentSeconds = rs.wasNull() ? null : seconds;    // wasNull() refers to the column read just before
                        result.add(new RegisteredTenant(tenantId, dbName, absentSeconds));
                    }
                }
                return result;
            }
        });
    }

    /** Starts the grace period of a registered tenant that is no longer configured (does nothing if it already started). */
    public void markAbsent(Cluster cluster, String tenantId) throws SQLException {
        withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET absent_since = now() WHERE scope = ? AND tenant_id = ? AND absent_since IS NULL")) {
                ps.setString(1, scope());
                ps.setString(2, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Forgets a tenant, after its database was dropped (or is deliberately kept for another tenant). */
    public void remove(Cluster cluster, String tenantId) throws SQLException {
        withConnection(cluster, connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE scope = ? AND tenant_id = ?")) {
                ps.setString(1, scope());
                ps.setString(2, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String scope() {
        return runtime.getOffboarding().getScope();
    }

    private <T> T withConnection(Cluster cluster, SqlWork<T> work) throws SQLException {
        try (Connection connection = provisioning.openAdminConnection(cluster)) {
            connection.setAutoCommit(true);
            ensureTable(connection, cluster);
            return work.run(connection);
        }
    }

    private void ensureTable(Connection connection, Cluster cluster) throws SQLException {
        String key = cluster.getHost() + ":" + cluster.getPort() + "/" + cluster.getAdminDatabase();
        if (tableEnsured.contains(key)) {
            return;
        }
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(" + DDL_LOCK_KEY + ")");
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "scope text NOT NULL, "
                    + "tenant_id text NOT NULL, "
                    + "db_name text NOT NULL, "
                    + "onboarded_at timestamptz NOT NULL DEFAULT now(), "
                    + "last_seen_at timestamptz NOT NULL DEFAULT now(), "
                    + "absent_since timestamptz, "
                    + "PRIMARY KEY (scope, tenant_id))");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        tableEnsured.add(key);
        log.info("Tenant registry table '{}' is available on {}:{}", TABLE, cluster.getHost(), cluster.getPort());
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
