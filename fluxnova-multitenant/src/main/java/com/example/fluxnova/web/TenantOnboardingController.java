package com.example.fluxnova.web;

import com.example.fluxnova.db.DatabaseProvisioningService;
import com.example.fluxnova.db.DatabaseProvisioningService.ClusterInfo;
import com.example.fluxnova.deploy.BpmnFolderWatcher;
import com.example.fluxnova.deploy.BpmnFolderWatcher.FolderStatus;
import com.example.fluxnova.engine.FluxnovaEngineManager;
import com.example.fluxnova.engine.FluxnovaEngineManager.PendingDropView;
import com.example.fluxnova.engine.FluxnovaEngineManager.TenantFailure;
import com.example.fluxnova.engine.TenantEngine;
import com.example.fluxnova.watch.TenantConfigWatcher;
import com.example.fluxnova.watch.TenantConfigWatcher.ConfigStatus;
import com.example.fluxnova.watch.TenantConfigWatcher.ScheduledOffboarding;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import org.finos.fluxnova.bpm.engine.ProcessEngine;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Read-only management view for sanity checks: which tenant engines are running, on which database, how their
 * pools are doing, and which tenants failed to start. It exposes no credentials and changes nothing.
 * <p>
 * Do not publish this controller on a public load balancer; keep it reachable from the VPC / operators only.
 */
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantOnboardingController {

    private final FluxnovaEngineManager engineManager;
    private final TenantConfigWatcher watcher;
    private final DatabaseProvisioningService provisioning;
    private final BpmnFolderWatcher bpmnFolder;

    /**
     * All running engines, all failed tenants, the state of the config file, the PostgreSQL cluster in use and the BPMN
     * deployment folder with what happened to the files that landed in it.
     */
    @GetMapping
    public Overview overview() {
        List<TenantSummary> active = engineManager.activeTenants().stream().map(this::summarize).toList();
        List<TenantFailure> failed = engineManager.failedTenants();
        return new Overview(watcher.status(), provisioning.lastKnownCluster(), active.size(), failed.size(), active, failed,
                watcher.scheduledOffboardings(), engineManager.pendingDatabaseDrops(), bpmnFolder.status());
    }

    /** One tenant, including live counts read from that tenant's own database. */
    @GetMapping("/{tenantId}")
    public TenantDetail detail(@PathVariable String tenantId) {
        TenantEngine tenant = engineManager.find(tenantId).orElse(null);
        if (tenant == null) {
            TenantFailure failure = engineManager.failedTenants().stream()
                    .filter(f -> f.tenantId().equals(tenantId)).findFirst().orElse(null);
            if (failure != null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Tenant '" + tenantId + "' is configured but not running (attempt " + failure.attempts()
                                + ", next retry " + failure.nextRetryAt() + "): " + failure.lastError());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tenant '" + tenantId + "'");
        }

        ProcessEngine engine = tenant.getEngine();
        return new TenantDetail(summarize(tenant),
                engine.getRepositoryService().createDeploymentQuery().count(),
                engine.getRepositoryService().createProcessDefinitionQuery().count(),
                engine.getRuntimeService().createProcessInstanceQuery().count(),
                engine.getManagementService().createJobQuery().count());
    }

    private TenantSummary summarize(TenantEngine tenant) {
        HikariPoolMXBean pool = tenant.getDataSource().getHikariPoolMXBean();
        PoolStats poolStats = pool == null ? null : new PoolStats(pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getTotalConnections(), pool.getThreadsAwaitingConnection(),
                tenant.getDataSource().getMaximumPoolSize());
        boolean jobExecutorActive = ((ProcessEngineConfigurationImpl) tenant.getEngine().getProcessEngineConfiguration())
                .getJobExecutor().isActive();
        return new TenantSummary(tenant.getTenantId(), tenant.getEngine().getName(), tenant.getDbName(),
                tenant.getJdbcUrl(), "RUNNING", tenant.getOnboardedAt(), tenant.isDatabaseCreated(), jobExecutorActive,
                poolStats);
    }

    /**
     * @param cluster               the PostgreSQL cluster the engines' databases live on (host, port, server version),
     *                              {@code null} until the cluster was reached for the first time
     * @param scheduledOffboardings tenants removed from the file that will be offboarded (engine stopped, database dropped)
     *                              when their grace period ends, unless they are added back
     * @param pendingDatabaseDrops  offboarded tenants whose database could not be dropped yet and is being retried
     * @param bpmnDeployment        the folder whose {@code .bpmn} files are deployed and started automatically, and the
     *                              most recent outcomes (newest first)
     */
    public record Overview(ConfigStatus config, ClusterInfo cluster, int activeCount, int failedCount, List<TenantSummary> active,
                           List<TenantFailure> failed, List<ScheduledOffboarding> scheduledOffboardings,
                           List<PendingDropView> pendingDatabaseDrops, FolderStatus bpmnDeployment) {
    }

    public record TenantSummary(String tenantId, String engineName, String dbName, String jdbcUrl, String state,
                                Instant onboardedAt, boolean databaseCreatedByThisNode, boolean jobExecutorActive,
                                PoolStats pool) {
    }

    public record PoolStats(int active, int idle, int total, int threadsAwaiting, int maximum) {
    }

    public record TenantDetail(TenantSummary tenant, long deployments, long processDefinitions,
                               long runningProcessInstances, long jobs) {
    }
}
