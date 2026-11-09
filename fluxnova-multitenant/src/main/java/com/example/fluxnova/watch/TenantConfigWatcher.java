package com.example.fluxnova.watch;

import com.example.fluxnova.config.RuntimeProperties;
import com.example.fluxnova.config.TenantConfigException;
import com.example.fluxnova.config.TenantConfigLoader;
import com.example.fluxnova.config.TenantConfigProperties;
import com.example.fluxnova.config.TenantConfigProperties.Cluster;
import com.example.fluxnova.config.TenantConfigProperties.Tenant;
import com.example.fluxnova.db.TenantRegistryService;
import com.example.fluxnova.db.TenantRegistryService.RegisteredTenant;
import com.example.fluxnova.engine.FluxnovaEngineManager;
import com.example.fluxnova.engine.TenantEngine;
import com.example.fluxnova.engine.TenantOnboardingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches {@code tenants.yml} and onboards tenants that appear in it, without restarting anything.
 * <p>
 * <b>Why polling and not {@code WatchService}:</b> in the target setup the file lives in a folder of the Windows host
 * that is bind-mounted into a Linux container (and on ECS typically on EFS or a ConfigMap-like volume). Those mounts
 * frequently never deliver inotify events, so a WatchService would silently miss edits. Reading and hashing a few kB
 * every {@code fluxnova.runtime.poll-interval} (default 15 s) works on every file system.
 * <p>
 * Every run:
 * <ol>
 *   <li>reads the file and compares its SHA-256 with the last one seen;</li>
 *   <li>if it changed, parses and validates it. An invalid file is logged once and ignored - the last valid
 *       configuration stays in force, so a typo or a half-saved file can never take tenants down;</li>
 *   <li>onboards every configured tenant that is not running yet. This also retries tenants whose earlier
 *       onboarding failed (with exponential back-off), e.g. while the cluster was unreachable.</li>
 * </ol>
 * Tenants that are already running are never touched by onboarding. A tenant that is <em>removed</em> from the file is
 * offboarded after a grace period: its engine is stopped and its database is dropped, see
 * {@link #offboardRemovedTenants}. The safety rules there matter because that destroys data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantConfigWatcher {

    private final RuntimeProperties runtime;
    private final TenantConfigLoader loader;
    private final FluxnovaEngineManager engineManager;
    private final TenantRegistryService registry;

    // The scheduler never overlaps runs of the same task, and checkForChanges() is synchronized as well, so the
    // plain fields below are only touched by one thread at a time.
    private String lastSeenHash;
    private TenantConfigProperties activeConfig;
    private String lastReadProblem;
    private final Set<String> warnedRemoved = new HashSet<>();
    private final Set<String> warnedConflicts = new HashSet<>();
    private boolean warnedRemoveAll;
    private boolean warnedOrphanRemoveAll;
    private String lastRegistryProblem;
    private boolean absencesReset;
    /** Tenants whose database was recorded in the registry by this process (avoids writing it again every run). */
    private final Set<String> registered = new HashSet<>();
    /** Running tenants that are missing from the file -> when that was first noticed. Read by the management endpoint. */
    private final Map<String, Instant> absentSince = new ConcurrentHashMap<>();
    /** Registered tenants without a running engine that are missing from the file. Read by the management endpoint. */
    private final Map<String, ScheduledOffboarding> orphanSchedule = new ConcurrentHashMap<>();

    private volatile ConfigStatus status;

    /** Runs at start-up (no initial delay) and then {@code poll-interval} after each run has finished. */
    @Scheduled(fixedDelayString = "${fluxnova.runtime.poll-interval:PT15S}")
    public synchronized void checkForChanges() {
        if (engineManager.isShuttingDown()) {
            return;
        }
        try {
            poll();
        } catch (RuntimeException | LinkageError e) {
            // Never let one bad run stop the watcher; the next run starts from a clean slate.
            log.error("Unexpected error while checking {}; will try again in the next run", runtime.getConfigPath(), e);
        }
    }

    /** Read-only view for the management endpoint. */
    public ConfigStatus status() {
        ConfigStatus current = status;
        return current != null ? current : new ConfigStatus(runtime.getConfigPath(), false, null, null, null, null, 0);
    }

    private void poll() {
        Path file = Path.of(runtime.getConfigPath());
        Instant now = Instant.now();
        byte[] content = read(file);

        Instant changedAt = status == null ? null : status.lastChangeAppliedAt();
        String lastError = status == null ? null : status.lastError();

        if (content != null) {
            String hash = sha256(content);
            if (!hash.equals(lastSeenHash)) {
                // Remember the hash before parsing so that an invalid file is reported once, not on every run.
                lastSeenHash = hash;
                log.info("Detected a new version of {} (sha256 {})", file, hash.substring(0, 12));
                try {
                    activeConfig = loader.parse(content);
                    changedAt = now;
                    lastError = null;
                    engineManager.resetRetryBackoff();
                    log.info("Loaded {}: {} tenant(s) configured", file, activeConfig.getFluxnova().getTenants().size());
                } catch (TenantConfigException e) {
                    lastError = e.getMessage();
                    log.error("Ignoring invalid {}; keeping the previous configuration ({} tenant(s)): {}", file,
                            activeConfig == null ? 0 : activeConfig.getFluxnova().getTenants().size(), e.getMessage());
                }
            }
        }

        if (activeConfig != null) {
            // "Current" = the file on disk is readable and was accepted. Removals are only acted on in that case.
            reconcile(activeConfig.getFluxnova(), content != null && lastError == null);
        }

        status = new ConfigStatus(runtime.getConfigPath(), content != null, lastSeenHash, now, changedAt, lastError,
                activeConfig == null ? 0 : activeConfig.getFluxnova().getTenants().size());
    }

    private void reconcile(TenantConfigProperties.Fluxnova config, boolean fileIsCurrent) {
        Cluster cluster = config.getCluster();
        Set<String> configured = new HashSet<>();

        for (Tenant tenant : config.getTenants()) {
            if (engineManager.isShuttingDown()) {
                return;
            }
            configured.add(tenant.getId());
            warnedRemoved.remove(tenant.getId());
            absentSince.remove(tenant.getId());
            engineManager.cancelPendingDrop(tenant.getId());   // back in the file: a drop that is still pending must not happen

            TenantEngine running = engineManager.find(tenant.getId()).orElse(null);
            if (running != null) {
                if (!running.getDbName().equals(tenant.getDbName())
                        && warnedConflicts.add(tenant.getId() + "->" + tenant.getDbName())) {
                    log.warn("Tenant '{}' runs on database '{}' but {} now says '{}'. A running tenant is never moved; "
                                    + "the change is ignored.", tenant.getId(), running.getDbName(),
                            runtime.getConfigPath(), tenant.getDbName());
                }
                continue;
            }
            if (!engineManager.isRetryDue(tenant.getId())) {
                continue;
            }
            try {
                engineManager.onboardNewTenant(cluster, tenant);
            } catch (TenantOnboardingException e) {
                // Already logged and recorded (with back-off) by the engine manager; carry on with the other tenants.
                log.debug("Onboarding of '{}' failed: {}", tenant.getId(), e.getMessage());
            }
        }

        engineManager.forgetFailuresExcept(configured);
        offboardRemovedTenants(config, configured, fileIsCurrent);
        engineManager.retryPendingDrops();
        registerRunningTenants(cluster, config, configured);
        offboardRegisteredOrphans(config, configured, fileIsCurrent);
    }

    /**
     * Records, in the cluster's tenant registry, the database of every configured tenant whose engine is running. Only
     * registered databases are ever dropped when their tenant disappears, and the registry outlives this process.
     */
    private void registerRunningTenants(Cluster cluster, TenantConfigProperties.Fluxnova config, Set<String> configured) {
        try {
            for (Tenant tenant : config.getTenants()) {
                TenantEngine running = engineManager.find(tenant.getId()).orElse(null);
                if (running != null && running.getDbName().equals(tenant.getDbName()) && !registered.contains(tenant.getId())) {
                    registry.register(cluster, tenant.getId(), tenant.getDbName());
                    registered.add(tenant.getId());
                }
            }
            registry.markPresent(cluster, configured);       // tenants that are back in the file are no longer waiting
            lastRegistryProblem = null;
        } catch (SQLException | RuntimeException e) {
            reportRegistryProblem("cannot record the running tenants", e);
        }
    }

    /**
     * Offboards the database of registered tenants that are no longer in the configuration and have <em>no engine
     * running</em>, which is what is left after a tenant was removed while the application was down or restarting. (A
     * removed tenant whose engine is still running is handled by {@link #offboardRemovedTenants}.)
     * <p>
     * Same safety rules as there, plus: the grace period is kept in the registry, so a restart does not skip it; a
     * database that another listed tenant now uses is never dropped; and databases that are not in the registry are
     * never touched.
     */
    private void offboardRegisteredOrphans(TenantConfigProperties.Fluxnova config, Set<String> configured, boolean fileIsCurrent) {
        RuntimeProperties.Offboarding policy = runtime.getOffboarding();
        Cluster cluster = config.getCluster();
        try {
            if (!fileIsCurrent) {
                orphanSchedule.clear();
                if (!absencesReset) {
                    registry.resetAbsences(cluster);          // the file is missing / being fixed: grace periods start over
                    absencesReset = true;
                }
                return;
            }
            absencesReset = false;
            if (!policy.isEnabled()) {
                orphanSchedule.clear();
                return;
            }

            List<RegisteredTenant> orphans = registry.missing(cluster, configured);
            orphans.removeIf(o -> engineManager.find(o.tenantId()).isPresent());     // running engines: handled above
            lastRegistryProblem = null;
            if (orphans.isEmpty()) {
                orphanSchedule.clear();
                warnedOrphanRemoveAll = false;
                return;
            }
            if (configured.isEmpty() && !policy.isAllowRemoveAll()) {
                orphanSchedule.clear();
                if (!warnedOrphanRemoveAll) {
                    warnedOrphanRemoveAll = true;
                    log.warn("{} lists no tenants at all. Dropping the {} registered tenant database(s) is switched off "
                            + "(fluxnova.runtime.offboarding.allow-remove-all=false).", runtime.getConfigPath(), orphans.size());
                }
                return;
            }
            warnedOrphanRemoveAll = false;

            Set<String> configuredDatabases = new HashSet<>();
            config.getTenants().forEach(t -> configuredDatabases.add(t.getDbName()));
            Instant now = Instant.now();
            Set<String> waiting = new HashSet<>();

            for (RegisteredTenant orphan : orphans) {
                if (engineManager.isShuttingDown()) {
                    return;
                }
                String tenantId = orphan.tenantId();
                if (configuredDatabases.contains(orphan.dbName())) {
                    registry.remove(cluster, tenantId);
                    registered.remove(tenantId);
                    log.info("Registered tenant '{}' is no longer listed, but its database '{}' is now used by another listed tenant: "
                            + "the database is kept.", tenantId, orphan.dbName());
                    continue;
                }
                if (engineManager.isDropPending(tenantId)) {
                    continue;                                   // a failed drop is already being retried with back-off
                }
                waiting.add(tenantId);
                if (orphan.absentSeconds() == null) {
                    registry.markAbsent(cluster, tenantId);
                    orphanSchedule.put(tenantId, new ScheduledOffboarding(tenantId, orphan.dbName(), now.plus(policy.getGracePeriod())));
                    log.warn("Registered tenant '{}' is not in {} and no engine runs for it. Its database '{}' will be DROPPED in {} s "
                                    + "unless the tenant is added back before then.", tenantId, runtime.getConfigPath(),
                            orphan.dbName(), policy.getGracePeriod().toSeconds());
                    continue;
                }
                long remainingSeconds = policy.getGracePeriod().toSeconds() - orphan.absentSeconds();
                if (remainingSeconds > 0) {
                    orphanSchedule.put(tenantId, new ScheduledOffboarding(tenantId, orphan.dbName(), now.plusSeconds(remainingSeconds)));
                    continue;
                }
                if (engineManager.dropOrphanedDatabase(tenantId, orphan.dbName(), cluster)
                        == FluxnovaEngineManager.OffboardOutcome.OFFBOARDED) {
                    registry.remove(cluster, tenantId);
                    registered.remove(tenantId);
                    waiting.remove(tenantId);
                }
            }
            orphanSchedule.keySet().retainAll(waiting);
        } catch (SQLException | RuntimeException e) {
            reportRegistryProblem("cannot check for databases of removed tenants", e);
        }
    }

    private void reportRegistryProblem(String what, Exception e) {
        String problem = what + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
        if (!problem.equals(lastRegistryProblem)) {
            lastRegistryProblem = problem;
            log.warn("Tenant registry problem - {}. Databases of tenants that are removed while no engine runs are not cleaned "
                    + "up until this works.", problem);
        }
    }

    /**
     * Offboards running tenants that are no longer in the configuration: engine stopped, pool closed, database dropped.
     * <p>
     * Because this destroys data, it only acts when all of these hold:
     * <ul>
     *   <li>the file as it is on disk right now is valid and applied ({@code fileIsCurrent}); while it is missing,
     *       unreadable or being fixed after a mistake, nothing is removed and the grace period starts over;</li>
     *   <li>the tenant has been absent for the whole grace period, so a mistaken save can be corrected, and putting the
     *       tenant back cancels the offboarding;</li>
     *   <li>if the list is empty, {@code fluxnova.runtime.offboarding.allow-remove-all} is on (the default). Switched off, an
     *       empty list is refused, as protection against emptying the file by accident.</li>
     * </ul>
     */
    private void offboardRemovedTenants(TenantConfigProperties.Fluxnova config, Set<String> configured, boolean fileIsCurrent) {
        RuntimeProperties.Offboarding policy = runtime.getOffboarding();
        if (!fileIsCurrent) {
            absentSince.clear();     // the file is missing / being fixed: the grace period starts over once it is valid again
            return;
        }
        List<TenantEngine> removed = engineManager.activeTenants().stream()
                .filter(t -> !configured.contains(t.getTenantId())).toList();
        Set<String> removedIds = new HashSet<>();
        removed.forEach(t -> removedIds.add(t.getTenantId()));
        absentSince.keySet().retainAll(removedIds);          // tenants that are back in the file are no longer waiting

        if (removed.isEmpty()) {
            warnedRemoveAll = false;
            return;
        }
        if (!policy.isEnabled()) {
            for (TenantEngine tenant : removed) {
                if (warnedRemoved.add(tenant.getTenantId())) {
                    log.warn("Tenant '{}' is no longer listed in {}, but offboarding is switched off: its engine keeps running "
                            + "and its database is kept.", tenant.getTenantId(), runtime.getConfigPath());
                }
            }
            return;
        }
        if (configured.isEmpty() && !policy.isAllowRemoveAll()) {
            if (!warnedRemoveAll) {
                warnedRemoveAll = true;
                log.warn("{} lists no tenants at all. Offboarding EVERY tenant at once is switched off "
                        + "(fluxnova.runtime.offboarding.allow-remove-all=false); all engines keep running.",
                        runtime.getConfigPath());
            }
            return;
        }
        warnedRemoveAll = false;

        Set<String> configuredDatabases = new HashSet<>();
        config.getTenants().forEach(t -> configuredDatabases.add(t.getDbName()));
        Instant now = Instant.now();
        for (TenantEngine tenant : removed) {
            String tenantId = tenant.getTenantId();
            Instant since = absentSince.get(tenantId);
            if (since == null) {
                absentSince.put(tenantId, now);
                log.warn("Tenant '{}' was removed from {}. It will be OFFBOARDED in {} s (engine stopped, database '{}' dropped) "
                                + "unless it is added back before then.", tenantId, runtime.getConfigPath(),
                        policy.getGracePeriod().toSeconds(), tenant.getDbName());
                since = now;
            }
            if (engineManager.isShuttingDown() || now.isBefore(since.plus(policy.getGracePeriod()))) {
                continue;
            }
            // If another listed tenant now uses this database name (a renamed tenant), the data is handed over, not dropped.
            boolean dropDatabase = !configuredDatabases.contains(tenant.getDbName());
            FluxnovaEngineManager.OffboardOutcome outcome =
                    engineManager.offboardTenant(tenantId, config.getCluster().getAdminDatabase(), dropDatabase);
            absentSince.remove(tenantId);
            warnedRemoved.remove(tenantId);
            registered.remove(tenantId);
            if (outcome == FluxnovaEngineManager.OffboardOutcome.OFFBOARDED) {
                forgetInRegistry(config.getCluster(), tenantId);
            }
            // A failed drop keeps its registry row: it is retried, and still found after a restart.
        }
    }

    private void forgetInRegistry(Cluster cluster, String tenantId) {
        try {
            registry.remove(cluster, tenantId);
        } catch (SQLException | RuntimeException e) {
            reportRegistryProblem("cannot update the registry after offboarding '" + tenantId + "'", e);
        }
    }

    /** Tenants that were removed from the file and are waiting out the grace period. For the management endpoint. */
    public List<ScheduledOffboarding> scheduledOffboardings() {
        Duration grace = runtime.getOffboarding().getGracePeriod();
        List<ScheduledOffboarding> list = new ArrayList<>();
        absentSince.forEach((tenantId, since) -> engineManager.find(tenantId)
                .ifPresent(t -> list.add(new ScheduledOffboarding(tenantId, t.getDbName(), since.plus(grace)))));
        list.addAll(orphanSchedule.values());
        list.sort(Comparator.comparing(ScheduledOffboarding::tenantId));
        return list;
    }

    /** @return the file content, or {@code null} if it is missing / unreadable right now (reported once per state). */
    private byte[] read(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            if (lastReadProblem != null) {
                log.info("{} is readable again", file);
                lastReadProblem = null;
            }
            return content;
        } catch (NoSuchFileException e) {
            reportReadProblem(file, "does not exist" + (activeConfig != null ? "; keeping the last valid configuration" : ""));
        } catch (IOException e) {
            reportReadProblem(file, "cannot be read: " + e);
        }
        return null;
    }

    private void reportReadProblem(Path file, String problem) {
        if (!problem.equals(lastReadProblem)) {
            lastReadProblem = problem;
            log.warn("{} {}", file, problem);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist on every JVM", e);
        }
    }

    /** A removed tenant that is waiting out the grace period; it is offboarded at {@code offboardAt} unless added back. */
    public record ScheduledOffboarding(String tenantId, String dbName, Instant offboardAt) {
    }

    /** What the watcher currently knows about the config file. */
    public record ConfigStatus(String path, boolean fileFound, String sha256, Instant lastCheckedAt,
                               Instant lastChangeAppliedAt, String lastError, int tenantsConfigured) {
    }
}
