package com.example.fluxnova.engine;

import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.ProcessEngines;

import java.time.Instant;

/**
 * Everything that belongs to exactly one tenant: its {@link ProcessEngine} and the {@link HikariDataSource}
 * the engine runs on. Nothing in here is shared with any other tenant.
 * <p>
 * The engine does <em>not</em> own the pool (an externally supplied DataSource is never closed by the engine),
 * so this class owns both and closes them together, in the only safe order: engine first, pool second.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public final class TenantEngine {

    private final String tenantId;
    private final String dbName;
    /** jdbc:postgresql://host:port/db - no credentials. */
    private final String jdbcUrl;
    private final ProcessEngine engine;
    private final HikariDataSource dataSource;
    private final Instant onboardedAt;
    /** True if this node created the database, false if it already existed. */
    private final boolean databaseCreated;

    /**
     * Stops the tenant. Each step is isolated so a failure in one never leaks the next resource.
     * <ol>
     *   <li>{@code engine.close()} stops the job executor (waits for jobs that are already running to finish),
     *       and unregisters the engine from the global {@link ProcessEngines} registry.</li>
     *   <li>The explicit {@code unregister} is idempotent and only guards against an engine that failed halfway.</li>
     *   <li>{@code dataSource.close()} closes every pooled connection. It must come last: closing the pool
     *       first would make the job executor's in-flight commands fail.</li>
     * </ol>
     */
    void close() {
        log.info("Closing engine and connection pool of tenant '{}'", tenantId);
        try {
            engine.close();
        } catch (RuntimeException e) {
            log.warn("Tenant '{}': error while closing process engine: {}", tenantId, e.toString());
        }
        try {
            ProcessEngines.unregister(engine);
        } catch (RuntimeException e) {
            log.warn("Tenant '{}': error while unregistering process engine: {}", tenantId, e.toString());
        }
        try {
            dataSource.close();
        } catch (RuntimeException e) {
            log.warn("Tenant '{}': error while closing connection pool: {}", tenantId, e.toString());
        }
    }
}
