package com.example.fluxnova.db;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.config.TenantConfigProperties.Cluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Makes sure a tenant's dedicated database exists on the shared PostgreSQL / RDS cluster, using plain JDBC.
 * <p>
 * This runs <em>before</em> the tenant's connection pool is created: a pool cannot be opened against a
 * database that does not exist yet, so a short-lived admin connection to the maintenance database
 * ({@code postgres} by default) is used instead and closed again as soon as the check / DDL is done.
 * <p>
 * Safe with several containers: ECS may run more than one task of this service and every task watches the
 * same {@code tenants.yml}. The check-then-create sequence is therefore wrapped in a session-level PostgreSQL
 * advisory lock keyed on the database name, and a lost race ({@code 42P04 duplicate_database}) is treated as
 * "already exists".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseProvisioningService {

    /**
     * Allowed database names: lower-case letters, digits and underscores, starting with a letter or underscore,
     * at most 63 characters (PostgreSQL's identifier limit). CREATE DATABASE cannot take a bind parameter, so the
     * name is interpolated into the statement; this pattern is what makes that safe.
     */
    public static final Pattern DB_NAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** Databases that exist on every PostgreSQL / RDS cluster. They are never dropped (the config loader also rejects them). */
    private static final Set<String> PROTECTED_DATABASES = Set.of("postgres", "template0", "template1", "rdsadmin");

    private static final String SQLSTATE_DUPLICATE_DATABASE = "42P04";
    private static final long LOCK_POLL_MILLIS = 250;

    private final RuntimeProperties runtime;

    /** The cluster as seen on the most recent admin connection; {@code null} until the cluster was reached once. */
    private volatile ClusterInfo lastCluster;

    /**
     * The PostgreSQL cluster the application is talking to. Contains no credentials.
     *
     * @param serverVersion the server's own version string, e.g. {@code 16.15 (Debian 16.15-1.pgdg13+1)}
     */
    public record ClusterInfo(String host, int port, String serverVersion, Instant lastConnectedAt) {

        private boolean sameServerAs(ClusterInfo other) {
            return host.equals(other.host) && port == other.port && Objects.equals(serverVersion, other.serverVersion);
        }
    }

    /** Where the application is connected and which PostgreSQL version runs there, or {@code null} if it never reached the cluster. */
    public ClusterInfo lastKnownCluster() {
        return lastCluster;
    }

    /**
     * Creates {@code dbName} if it does not exist yet.
     *
     * @return {@code true} if this call created the database, {@code false} if it already existed
     * @throws SQLException             if the cluster is unreachable, credentials / privileges are insufficient, or the
     *                                  advisory lock cannot be obtained within {@code fluxnova.runtime.lock-timeout}
     * @throws IllegalArgumentException if {@code dbName} is not a safe identifier
     */
    public boolean ensureDatabaseExists(Cluster cluster, String dbName) throws SQLException {
        requireSafeIdentifier(dbName);
        long lockKey = advisoryLockKey(dbName);

        // try-with-resources: the admin connection is closed on every path, which also releases the advisory lock.
        try (Connection admin = openAdminConnection(cluster)) {
            // CREATE DATABASE is not allowed inside a transaction block, so stay in auto-commit mode.
            admin.setAutoCommit(true);
            acquireAdvisoryLock(admin, lockKey, dbName);
            try {
                if (databaseExists(admin, dbName)) {
                    log.info("Database '{}' already exists on {}:{}", dbName, cluster.getHost(), cluster.getPort());
                    return false;
                }
                createDatabase(admin, dbName);
                log.info("Created database '{}' on {}:{}", dbName, cluster.getHost(), cluster.getPort());
                return true;
            } catch (SQLException e) {
                if (SQLSTATE_DUPLICATE_DATABASE.equals(e.getSQLState())) {
                    log.info("Database '{}' was created concurrently by another node", dbName);
                    return false;
                }
                throw e;
            } finally {
                releaseAdvisoryLock(admin, lockKey, dbName);
            }
        }
    }

    /**
     * Drops {@code dbName} if it exists, for a tenant that is being offboarded. <b>All data in the database is lost.</b>
     * <p>
     * The caller must have closed its own connection pool first. Sessions of other nodes that are still attached (e.g.
     * the same tenant's pool in another ECS task) are disconnected by {@code WITH (FORCE)}. The same advisory lock as
     * {@link #ensureDatabaseExists} is held, so a drop never interleaves with a create of the same database elsewhere.
     *
     * @return {@code true} if this call dropped the database, {@code false} if it did not exist
     * @throws SQLException             if the cluster is unreachable or the user may not drop the database (it must own it)
     * @throws IllegalArgumentException if the name is unsafe, or is a system / maintenance database that is never dropped
     */
    public boolean dropDatabase(Cluster cluster, String dbName) throws SQLException {
        requireSafeIdentifier(dbName);
        if (PROTECTED_DATABASES.contains(dbName) || dbName.equals(cluster.getAdminDatabase())) {
            throw new IllegalArgumentException("Refusing to drop the protected database '" + dbName + "'");
        }
        long lockKey = advisoryLockKey(dbName);

        try (Connection admin = openAdminConnection(cluster)) {
            admin.setAutoCommit(true);   // DROP DATABASE is not allowed inside a transaction block
            acquireAdvisoryLock(admin, lockKey, dbName);
            try {
                if (!databaseExists(admin, dbName)) {
                    log.info("Database '{}' does not exist on {}:{}, nothing to drop", dbName, cluster.getHost(), cluster.getPort());
                    return false;
                }
                try (Statement st = admin.createStatement()) {
                    // dbName was validated against DB_NAME_PATTERN, so quoting it is purely belt and braces.
                    st.execute("DROP DATABASE \"" + dbName + "\" WITH (FORCE)");
                }
                log.warn("Dropped database '{}' on {}:{}", dbName, cluster.getHost(), cluster.getPort());
                return true;
            } finally {
                releaseAdvisoryLock(admin, lockKey, dbName);
            }
        }
    }

    /** JDBC URL for a tenant database. Contains no credentials. */
    public static String jdbcUrl(Cluster cluster, String dbName) {
        return "jdbc:postgresql://" + cluster.getHost() + ":" + cluster.getPort() + "/" + dbName;
    }

    /** A short-lived admin connection to the maintenance database. Package-private: also used by the tenant registry. */
    Connection openAdminConnection(Cluster cluster) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", cluster.getAdminUser());
        props.setProperty("password", cluster.getAdminPassword());
        props.setProperty("ApplicationName", "fluxnova-provisioner");
        props.setProperty("connectTimeout", String.valueOf(seconds(runtime.getConnectTimeout())));
        // Upper bound for a single statement round trip; CREATE DATABASE normally takes well under a second.
        props.setProperty("socketTimeout", "120");

        String url = jdbcUrl(cluster, cluster.getAdminDatabase());
        log.debug("Opening admin connection to {}", url);
        Connection connection = DriverManager.getConnection(url, props);
        noteCluster(cluster, connection);
        return connection;
    }

    /**
     * Remembers which server this connection reached. The driver already knows the server version from the connection
     * handshake, so this costs no query. It is logged when it changes (first connection, or the cluster was upgraded).
     */
    private void noteCluster(Cluster cluster, Connection connection) {
        String version = null;
        try {
            version = connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            log.debug("Could not read the PostgreSQL server version: {}", e.getMessage());
        }
        ClusterInfo current = new ClusterInfo(cluster.getHost(), cluster.getPort(), version, Instant.now());
        ClusterInfo previous = lastCluster;
        lastCluster = current;
        if (previous == null || !previous.sameServerAs(current)) {
            log.info("Connected to PostgreSQL cluster {}:{}, server version {}", current.host(), current.port(),
                    version == null ? "unknown" : version);
        }
    }

    private boolean databaseExists(Connection admin, String dbName) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void createDatabase(Connection admin, String dbName) throws SQLException {
        // dbName was validated against DB_NAME_PATTERN, so quoting it is purely belt and braces.
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE \"" + dbName + "\"");
        }
    }

    private void acquireAdvisoryLock(Connection admin, long key, String dbName) throws SQLException {
        long deadline = System.nanoTime() + runtime.getLockTimeout().toNanos();
        while (true) {
            try (PreparedStatement ps = admin.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new SQLException("Timed out after " + runtime.getLockTimeout()
                        + " waiting for the provisioning lock of database '" + dbName
                        + "' (another node is provisioning it)");
            }
            log.debug("Provisioning lock of database '{}' is held by another node, waiting", dbName);
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the provisioning lock of database '" + dbName + "'", e);
            }
        }
    }

    private void releaseAdvisoryLock(Connection admin, long key, String dbName) {
        try (PreparedStatement ps = admin.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        } catch (SQLException e) {
            // Not fatal: closing the connection (right after this) releases session-level advisory locks anyway.
            log.warn("Could not explicitly release provisioning lock of database '{}': {}", dbName, e.getMessage());
        }
    }

    /** Stable 64-bit key derived from the database name, identical on every node. */
    static long advisoryLockKey(String dbName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("fluxnova-provision:" + dbName).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist on every JVM", e);
        }
    }

    private static void requireSafeIdentifier(String dbName) {
        if (dbName == null || !DB_NAME_PATTERN.matcher(dbName).matches()) {
            throw new IllegalArgumentException("Unsafe database name: '" + dbName + "'");
        }
    }

    private static long seconds(Duration d) {
        return Math.max(1, d.toSeconds());
    }
}
