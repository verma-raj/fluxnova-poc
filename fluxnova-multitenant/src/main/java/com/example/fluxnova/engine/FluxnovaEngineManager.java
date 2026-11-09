package com.example.fluxnova.engine;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.config.TenantConfigProperties.Cluster;
import com.example.fluxnova.config.TenantConfigProperties.Tenant;
import com.example.fluxnova.db.DatabaseProvisioningService;
import com.example.fluxnova.portal.PortalAdminBootstrap;
import com.example.fluxnova.portal.TenantEngineDirectory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.ProcessEngineConfiguration;
import org.finos.fluxnova.bpm.engine.ProcessEngines;
import org.finos.fluxnova.bpm.engine.authorization.Groups;
import org.finos.fluxnova.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.finos.fluxnova.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.finos.fluxnova.bpm.engine.impl.plugin.AdministratorAuthorizationPlugin;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates the tenant engines: one {@link ProcessEngine} per tenant, each on its own PostgreSQL database
 * and its own HikariCP pool, all inside this JVM.
 *
 * <h2>Lifecycle of a tenant's connection pool</h2>
 * <ol>
 *   <li><b>Create.</b> {@link #onboardNewTenant} first lets {@link DatabaseProvisioningService} create the database
 *       (a pool cannot open against a database that does not exist). Only then is a {@link HikariDataSource}
 *       built for that tenant. Hikari connects eagerly, so a wrong host or password fails right here and not on
 *       the first request.</li>
 *   <li><b>Adopt.</b> The pool is handed to the tenant's engine configuration. The engine borrows connections
 *       from it but never closes it - ownership stays with {@link TenantEngine}.</li>
 *   <li><b>Use.</b> The engine's command executor and job executor borrow and return connections. Hikari grows
 *       the pool up to {@code pool.maximum-size} and shrinks it back to {@code pool.minimum-idle}; connections
 *       are recycled after {@code pool.max-lifetime}, which is also how RDS failovers / password rotations are
 *       picked up without a restart.</li>
 *   <li><b>Close.</b> {@link TenantEngine#close()}: engine first (the job executor drains), pool second.
 *       The same happens if onboarding fails halfway (nothing is leaked) and for every tenant on shutdown
 *       (SIGTERM from ECS triggers {@link #shutdown()}).</li>
 * </ol>
 *
 * <h2>Why tenants cannot see or disturb each other</h2>
 * <ul>
 *   <li><b>Data:</b> a PostgreSQL connection is bound to one database for its entire life and SQL cannot address a
 *       second database. A tenant's pool only ever holds connections to that tenant's database, so even a bug
 *       cannot read or write another tenant's rows - no tenant-id column or filter is involved.</li>
 *   <li><b>Resources:</b> pools are separate objects, so one tenant exhausting its connections only makes that
 *       tenant wait ({@code connection-timeout}); other pools are unaffected. Each engine also has its own job
 *       executor with its own bounded thread pool, so a flood of jobs in one tenant cannot starve another.</li>
 *   <li><b>Engine state:</b> every {@code StandaloneProcessEngineConfiguration} builds its own MyBatis session
 *       factory, deployment / process-definition caches, id generator and expression / script resolution context.
 *       Nothing is static or shared, with one exception: the global {@link ProcessEngines} name registry.</li>
 *   <li><b>Onboarding one tenant never touches another:</b> adding a tenant only adds entries; running engines are
 *       not rebuilt, paused or reconfigured, so their in-flight process instances and jobs keep running.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * {@code ProcessEngines} keeps its registry in a plain {@code HashMap} whose register / unregister methods are not
 * synchronized, and building an engine registers it. All engine builds, registrations and closes therefore run under
 * one {@link #lifecycleLock}. The lock is only held for lifecycle changes: request threads read the lock-free
 * {@link #engines} map and never wait on a tenant that is being onboarded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FluxnovaEngineManager {

    private final RuntimeProperties runtime;
    private final DatabaseProvisioningService provisioning;
    private final PortalAdminBootstrap portalAdmin;

    /** tenantId -> running tenant. Written only under {@link #lifecycleLock}, read lock-free. */
    private final Map<String, TenantEngine> engines = new ConcurrentHashMap<>();
    /** tenantId -> last onboarding failure, for tenants that are in the config but not (yet) running. */
    private final Map<String, TenantFailure> failures = new ConcurrentHashMap<>();
    /** tenantId -> database of an offboarded tenant that is still to be dropped (a drop failed and is retried). */
    private final Map<String, PendingDrop> pendingDrops = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean shuttingDown;

    /**
     * Provisions the database, builds the pool and engine for a tenant that is not running yet, and makes it
     * available. Idempotent: onboarding a tenant that already runs against the same database returns it unchanged.
     *
     * @throws TenantOnboardingException if anything fails; everything created so far is cleaned up and the failure
     *                                   is recorded (see {@link #failedTenants()}) so the watcher can retry with back-off
     */
    public TenantEngine onboardNewTenant(Cluster cluster, Tenant tenant) {
        final String tenantId = tenant.getId();
        final String dbName = tenant.getDbName();

        lifecycleLock.lock();
        try {
            if (shuttingDown) {
                throw new TenantOnboardingException(tenantId, "the application is shutting down");
            }
            TenantEngine existing = engines.get(tenantId);
            if (existing != null) {
                if (existing.getDbName().equals(dbName)) {
                    return existing;
                }
                throw new TenantOnboardingException(tenantId, "is already running on database '" + existing.getDbName()
                        + "'; moving it to '" + dbName + "' is not supported at runtime");
            }
            for (TenantEngine other : engines.values()) {
                if (other.getDbName().equals(dbName)) {
                    throw new TenantOnboardingException(tenantId, "database '" + dbName
                            + "' already belongs to tenant '" + other.getTenantId() + "'");
                }
            }
            return doOnboard(cluster, tenantId, dbName);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private TenantEngine doOnboard(Cluster cluster, String tenantId, String dbName) {
        long startedNanos = System.nanoTime();
        HikariDataSource dataSource = null;
        ProcessEngine engine = null;
        try {
            log.info("Onboarding tenant '{}' (database '{}')", tenantId, dbName);

            // 1. The database must exist before a pool can connect to it.
            boolean databaseCreated = provisioning.ensureDatabaseExists(cluster, dbName);

            // 2. A pool of its own for this tenant only.
            dataSource = createDataSource(cluster, tenantId, dbName);

            // 3. An engine of its own on that pool. DB_SCHEMA_UPDATE_TRUE creates the Fluxnova tables in a fresh
            //    database and upgrades an older schema, so this step is also the schema initialisation.
            engine = createEngine(tenantId, dataSource);

            // 4. Register globally under the tenant id. buildProcessEngine() already registers the engine; the
            //    explicit call is idempotent and keeps the registration visible in the code.
            ProcessEngines.registerProcessEngine(engine);

            // 5. A portal administrator in this tenant's own user tables, so the engine can be used in the web portal.
            portalAdmin.ensureAdministrator(tenantId, engine);

            TenantEngine tenant = new TenantEngine(tenantId, dbName, DatabaseProvisioningService.jdbcUrl(cluster, dbName),
                    engine, dataSource, Instant.now(), databaseCreated);
            engines.put(tenantId, tenant);
            failures.remove(tenantId);
            publishToPortal();

            log.info("Tenant '{}' is ready in {} ms (database {}, pool max {}, job executor {})", tenantId,
                    (System.nanoTime() - startedNanos) / 1_000_000, databaseCreated ? "created" : "existing",
                    runtime.getPool().getMaximumSize(), runtime.getJobs().isActivate() ? "active" : "inactive");
            return tenant;
        } catch (Exception | LinkageError e) {
            // Release whatever was built before the failure: engine (stops its job executor), then pool.
            closeQuietly(tenantId, engine, dataSource);
            TenantFailure failure = recordFailure(tenantId, dbName, e);
            if (failure.attempts() == 1) {
                log.error("Failed to onboard tenant '{}'; will retry at {}", tenantId, failure.nextRetryAt(), e);
            } else {
                log.warn("Failed to onboard tenant '{}' (attempt {}); will retry at {}: {}", tenantId,
                        failure.attempts(), failure.nextRetryAt(), failure.lastError());
            }
            throw new TenantOnboardingException(tenantId, failure.lastError(), e);
        }
    }

    private HikariDataSource createDataSource(Cluster cluster, String tenantId, String dbName) {
        RuntimeProperties.Pool pool = runtime.getPool();
        HikariConfig config = new HikariConfig();
        config.setPoolName("fluxnova-pool-" + tenantId);
        config.setJdbcUrl(DatabaseProvisioningService.jdbcUrl(cluster, dbName));
        config.setUsername(cluster.getAdminUser());
        config.setPassword(cluster.getAdminPassword());
        config.setMaximumPoolSize(pool.getMaximumSize());
        config.setMinimumIdle(pool.getMinimumIdle());
        config.setConnectionTimeout(pool.getConnectionTimeout().toMillis());
        config.setIdleTimeout(pool.getIdleTimeout().toMillis());
        config.setMaxLifetime(pool.getMaxLifetime().toMillis());
        // Shows up in pg_stat_activity, so connections can be attributed to a tenant on the RDS side.
        config.addDataSourceProperty("ApplicationName", "fluxnova:" + tenantId);
        return new HikariDataSource(config);
    }

    private ProcessEngine createEngine(String tenantId, HikariDataSource dataSource) {
        StandaloneProcessEngineConfiguration configuration = new StandaloneProcessEngineConfiguration();
        configuration.setProcessEngineName(tenantId);
        configuration.setDataSource(dataSource);
        configuration.setDatabaseType("postgres");
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        // Portal users are checked against the engine's authorizations. Code that runs without an authenticated user
        // (the job executor, this application's own services) is not subject to them.
        configuration.setAuthorizationEnabled(true);
        AdministratorAuthorizationPlugin administrators = new AdministratorAuthorizationPlugin();
        administrators.setAdministratorGroupName(Groups.CAMUNDA_ADMIN);
        configuration.setProcessEnginePlugins(List.of(administrators));
        configuration.setJobExecutor(new TenantJobExecutor(tenantId, runtime.getJobs()));
        configuration.setJobExecutorActivate(runtime.getJobs().isActivate());
        return configuration.buildProcessEngine();
    }

    /** The running tenant, if any. Lock-free; safe to call from request threads. */
    public Optional<TenantEngine> find(String tenantId) {
        return Optional.ofNullable(engines.get(tenantId));
    }

    /** All running tenants ordered by id. */
    public List<TenantEngine> activeTenants() {
        List<TenantEngine> list = new ArrayList<>(engines.values());
        list.sort(Comparator.comparing(TenantEngine::getTenantId));
        return list;
    }

    /** Tenants that are configured but failed to start, ordered by id. */
    public List<TenantFailure> failedTenants() {
        List<TenantFailure> list = new ArrayList<>(failures.values());
        list.sort(Comparator.comparing(TenantFailure::tenantId));
        return list;
    }

    /** How an {@link #offboardTenant} call ended. */
    public enum OffboardOutcome {
        /** The engine was stopped, its pool closed and its database dropped. */
        OFFBOARDED,
        /** The engine is stopped and its pool closed, but the database could not be dropped yet; it is retried with back-off. */
        DATABASE_DROP_PENDING,
        /** No engine with that id was running. */
        NOT_RUNNING
    }

    /**
     * Offboards a tenant that was removed from the configuration: stops its engine, closes its connection pool and
     * <b>drops its database</b>. All of the tenant's data is lost.
     * <p>
     * Order matters: the engine is taken out of the web portal first, then stopped (its job executor drains), then its
     * pool is closed (an open pool would block the drop), and only then is the database dropped. The credentials used to
     * drop are the ones the tenant's own pool used, so the drop always targets the cluster the tenant was created on.
     * If the drop fails (cluster unreachable, missing privilege, ...), the failure is remembered and retried with
     * back-off by {@link #retryPendingDrops()}; the engine stays stopped meanwhile.
     *
     * @param adminDatabase maintenance database to connect to for the DROP (normally {@code postgres})
     */
    public OffboardOutcome offboardTenant(String tenantId, String adminDatabase) {
        return offboardTenant(tenantId, adminDatabase, true);
    }

    /**
     * Like {@link #offboardTenant(String, String)}, but with {@code dropDatabase = false} only the engine is stopped and
     * the pool closed, and the database is left alone. Used when another tenant in the configuration now uses the same
     * database name (a tenant that was renamed): the new tenant takes the data over.
     */
    public OffboardOutcome offboardTenant(String tenantId, String adminDatabase, boolean dropDatabase) {
        lifecycleLock.lock();
        try {
            TenantEngine tenant = engines.get(tenantId);
            if (tenant == null) {
                return OffboardOutcome.NOT_RUNNING;
            }
            if (dropDatabase) {
                log.warn("Offboarding tenant '{}': stopping its engine and DROPPING database '{}' ({})",
                        tenantId, tenant.getDbName(), describeWork(tenant));
            } else {
                log.warn("Offboarding tenant '{}': stopping its engine; database '{}' is KEPT because another tenant in the "
                        + "configuration uses it ({})", tenantId, tenant.getDbName(), describeWork(tenant));
            }

            Cluster cluster = clusterOf(tenant, adminDatabase);     // read the credentials before the pool is closed
            engines.remove(tenantId);
            publishToPortal();                                       // no new portal / REST request reaches the engine
            tenant.close();                                          // engine first (job executor drains), pool second
            failures.remove(tenantId);
            if (!dropDatabase) {
                return OffboardOutcome.OFFBOARDED;
            }
            return dropOrSchedule(tenantId, tenant.getDbName(), cluster);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Drops the database of a tenant that no engine is running for, e.g. because it was removed from the configuration
     * while the application was down. The caller found it in the tenant registry. Same failure handling as
     * {@link #offboardTenant}: a failed drop is retried with back-off.
     *
     * @return {@link OffboardOutcome#OFFBOARDED} if the database is gone now (dropped, or it did not exist any more),
     *         {@link OffboardOutcome#DATABASE_DROP_PENDING} if the drop failed and will be retried
     * @throws IllegalStateException if an engine for this tenant is running: then it is not an orphan and must be
     *                               offboarded with {@link #offboardTenant} so that the engine is stopped first
     */
    public OffboardOutcome dropOrphanedDatabase(String tenantId, String dbName, Cluster cluster) {
        lifecycleLock.lock();
        try {
            if (engines.containsKey(tenantId)) {
                throw new IllegalStateException("Tenant '" + tenantId + "' has a running engine; offboard it instead of dropping its database");
            }
            log.warn("Offboarding tenant '{}': no engine is running for it, DROPPING its registered database '{}'", tenantId, dbName);
            return dropOrSchedule(tenantId, dbName, cluster);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** {@code true} while a failed database drop of this tenant is waiting for its next retry. */
    public boolean isDropPending(String tenantId) {
        return pendingDrops.containsKey(tenantId);
    }

    /** Retries database drops of offboarded tenants that failed before. Call regularly; it is cheap when nothing is pending. */
    public void retryPendingDrops() {
        if (pendingDrops.isEmpty()) {
            return;
        }
        lifecycleLock.lock();
        try {
            Instant now = Instant.now();
            for (PendingDrop pending : new ArrayList<>(pendingDrops.values())) {
                if (shuttingDown) {
                    return;
                }
                if (engines.containsKey(pending.tenantId())) {
                    pendingDrops.remove(pending.tenantId());          // the tenant was onboarded again: its database is in use
                    continue;
                }
                if (!now.isBefore(pending.nextRetryAt())) {
                    dropOrSchedule(pending.tenantId(), pending.dbName(), pending.cluster());
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** The tenant is back in the configuration: its database must not be dropped any more. */
    public void cancelPendingDrop(String tenantId) {
        PendingDrop removed = pendingDrops.remove(tenantId);
        if (removed != null) {
            log.warn("Tenant '{}' is back in the configuration: the pending drop of database '{}' is cancelled and its data is kept",
                    tenantId, removed.dbName());
        }
    }

    /** Offboarded tenants whose database still has to be dropped, ordered by id. Contains no credentials. */
    public List<PendingDropView> pendingDatabaseDrops() {
        List<PendingDropView> list = new ArrayList<>();
        pendingDrops.values().forEach(p -> list.add(new PendingDropView(p.tenantId(), p.dbName(), p.attempts(), p.lastError(), p.nextRetryAt())));
        list.sort(Comparator.comparing(PendingDropView::tenantId));
        return list;
    }

    private OffboardOutcome dropOrSchedule(String tenantId, String dbName, Cluster cluster) {
        try {
            boolean dropped = provisioning.dropDatabase(cluster, dbName);
            pendingDrops.remove(tenantId);
            log.warn("Tenant '{}' offboarded: engine stopped, connection pool closed, database '{}' {}",
                    tenantId, dbName, dropped ? "dropped" : "was already gone");
            return OffboardOutcome.OFFBOARDED;
        } catch (SQLException | RuntimeException e) {
            Instant now = Instant.now();
            String summary = summarize(e);
            PendingDrop pending = pendingDrops.compute(tenantId, (id, previous) -> {
                int attempts = previous == null ? 1 : previous.attempts() + 1;
                long delayMillis = Math.min(
                        runtime.getRetryInitialDelay().toMillis() * (1L << Math.min(attempts - 1, 20)),
                        runtime.getRetryMaxDelay().toMillis());
                return new PendingDrop(id, dbName, cluster, attempts, summary, now.plusMillis(delayMillis));
            });
            log.error("Tenant '{}': engine stopped, but database '{}' could not be dropped (attempt {}); next try at {}: {}",
                    tenantId, dbName, pending.attempts(), pending.nextRetryAt(), summary);
            return OffboardOutcome.DATABASE_DROP_PENDING;
        }
    }

    /** What is lost with the tenant, for the log line that precedes the drop. */
    private static String describeWork(TenantEngine tenant) {
        try {
            long instances = tenant.getEngine().getRuntimeService().createProcessInstanceQuery().count();
            long jobs = tenant.getEngine().getManagementService().createJobQuery().count();
            return instances + " running process instance(s), " + jobs + " job(s)";
        } catch (RuntimeException e) {
            return "contents could not be counted: " + e.getMessage();
        }
    }

    /** The cluster a tenant runs on, as its own pool sees it (host, port, credentials). */
    private static Cluster clusterOf(TenantEngine tenant, String adminDatabase) {
        URI uri = URI.create(tenant.getJdbcUrl().substring("jdbc:".length()));
        Cluster cluster = new Cluster();
        cluster.setHost(uri.getHost());
        cluster.setPort(uri.getPort());
        cluster.setAdminUser(tenant.getDataSource().getUsername());
        cluster.setAdminPassword(tenant.getDataSource().getPassword());
        cluster.setAdminDatabase(adminDatabase == null || adminDatabase.isBlank() ? "postgres" : adminDatabase);
        return cluster;
    }

    /** {@code true} if the tenant has no recorded failure, or its back-off period has elapsed. */
    public boolean isRetryDue(String tenantId) {
        TenantFailure failure = failures.get(tenantId);
        return failure == null || !Instant.now().isBefore(failure.nextRetryAt());
    }

    /** Called when the config file changes: an edit may have fixed the cause, so do not make the operator wait. */
    public void resetRetryBackoff() {
        Instant now = Instant.now();
        failures.replaceAll((id, failure) -> failure.withNextRetryAt(now));
    }

    /** Drops failure records of tenants that were removed from the config file. */
    public void forgetFailuresExcept(Set<String> configuredTenantIds) {
        failures.keySet().removeIf(id -> !configuredTenantIds.contains(id));
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Closes every tenant. Runs on SIGTERM (ECS stop) after the web server has drained, and waits for an onboarding
     * that is currently in progress. Safe to call more than once.
     */
    @PreDestroy
    public void shutdown() {
        lifecycleLock.lock();
        try {
            shuttingDown = true;
            if (engines.isEmpty()) {
                return;
            }
            log.info("Shutting down {} tenant engine(s)", engines.size());
            List<TenantEngine> running = new ArrayList<>(engines.values());
            engines.clear();   // stop handing the engines out before they are closed
            publishToPortal();
            for (TenantEngine tenant : running) {
                tenant.close();
            }
            log.info("All tenant engines closed");
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Gives the web portal and the REST API a fresh snapshot of the running engines. Call under the lifecycle lock. */
    private void publishToPortal() {
        Map<String, ProcessEngine> running = new HashMap<>();
        engines.forEach((id, tenant) -> running.put(id, tenant.getEngine()));
        TenantEngineDirectory.publish(running);
    }

    private TenantFailure recordFailure(String tenantId, String dbName, Throwable cause) {
        Instant now = Instant.now();
        String summary = summarize(cause);
        return failures.compute(tenantId, (id, previous) -> {
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            long delayMillis = Math.min(
                    runtime.getRetryInitialDelay().toMillis() * (1L << Math.min(attempts - 1, 20)),
                    runtime.getRetryMaxDelay().toMillis());
            return new TenantFailure(id, dbName, attempts, summary, now, now.plusMillis(delayMillis));
        });
    }

    private static String summarize(Throwable cause) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(cause);
        String text = root.getClass().getSimpleName() + ": " + root.getMessage();
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static void closeQuietly(String tenantId, ProcessEngine engine, HikariDataSource dataSource) {
        if (engine != null) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                log.warn("Tenant '{}': error while closing partially built engine: {}", tenantId, e.toString());
            }
        }
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (RuntimeException e) {
                log.warn("Tenant '{}': error while closing partially built pool: {}", tenantId, e.toString());
            }
        }
    }

    /** An offboarded tenant whose database could not be dropped yet. Holds the credentials needed to retry, so it stays internal. */
    private record PendingDrop(String tenantId, String dbName, Cluster cluster, int attempts, String lastError,
                               Instant nextRetryAt) {
    }

    /** {@link PendingDrop} without credentials, safe to show in the management API. */
    public record PendingDropView(String tenantId, String dbName, int attempts, String lastError, Instant nextRetryAt) {
    }

    /** Why a configured tenant is not running, and when it will be retried. */
    public record TenantFailure(String tenantId, String dbName, int attempts, String lastError,
                                Instant lastAttemptAt, Instant nextRetryAt) {
        TenantFailure withNextRetryAt(Instant next) {
            return new TenantFailure(tenantId, dbName, attempts, lastError, lastAttemptAt, next);
        }
    }

    /**
     * The stock job executor, bounded per tenant and with the tenant id in the name of its acquisition thread
     * ({@code fluxnova-jobs-tenant-a}) so thread dumps show which tenant a thread works for.
     */
    private static final class TenantJobExecutor extends DefaultJobExecutor {
        TenantJobExecutor(String tenantId, RuntimeProperties.Jobs jobs) {
            this.name = "fluxnova-jobs-" + tenantId;
            setCorePoolSize(jobs.getCorePoolSize());
            setMaxPoolSize(jobs.getMaxPoolSize());
            setQueueSize(jobs.getQueueSize());
            setMaxJobsPerAcquisition(jobs.getMaxJobsPerAcquisition());
        }
    }
}
