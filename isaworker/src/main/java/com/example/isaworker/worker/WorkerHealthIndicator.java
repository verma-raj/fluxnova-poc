package com.example.isaworker.worker;

import com.example.isaworker.config.EngineConfigReader;
import com.example.isaworker.config.EngineConfigReader.ConfigStatus;
import com.example.isaworker.config.EngineConfigWatcher;
import com.example.isaworker.config.EngineConfigWatcher.WatchStatus;
import com.example.isaworker.gui.DialogService;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator.SkippedTenant;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator.Status;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator.TenantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Part of {@code /actuator/health}. Idle mode (no tenant configured) is a legitimate, healthy state, so it reports UP;
 * the details tell an operator whether the worker is idle, which tenants it serves, which entries were skipped, whether
 * {@code fluxnova-engine.yml} was found and read at start-up or ignored as invalid, and whether it is watched (a change
 * restarts the application) and whether its newest version was ignored as invalid.
 * It shows no credentials.
 */
@Component
@RequiredArgsConstructor
public class WorkerHealthIndicator implements HealthIndicator {

    private final MultiTenantWorkerOrchestrator orchestrator;
    private final EngineConfigReader reader;
    private final ObjectProvider<EngineConfigWatcher> watcher;   // absent when fluxnova-workers.config.restart-on-change=false
    private final DialogService dialogs;

    @Override
    public Health health() {
        Status status = orchestrator.status();
        Health.Builder health = Health.up().withDetail("mode", !status.initialised() ? "starting" : status.idle() ? "idle" : "active");
        Map<String, Object> tenants = new LinkedHashMap<>();
        for (TenantStatus tenant : status.tenants()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("baseUrl", tenant.baseUrl());
            detail.put("topics", tenant.topics());
            detail.put("polling", tenant.polling());
            tenants.put(tenant.tenantId(), detail);
        }
        health.withDetail("tenants", tenants);
        if (!status.skipped().isEmpty()) {
            Map<String, String> skipped = new LinkedHashMap<>();
            for (SkippedTenant entry : status.skipped()) {
                skipped.put(entry.entry(), entry.reason());
            }
            health.withDetail("skippedEntries", skipped);
        }

        ConfigStatus config = reader.status();
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", config.path());
        file.put("fileFound", config.fileFound());
        file.put("loadedAt", config.loadedAt());
        file.put("tenantsConfigured", config.tenantsConfigured());
        if (config.error() != null) {
            file.put("error", config.error());               // the file was ignored: the application started without tenants
        }
        EngineConfigWatcher active = watcher.getIfAvailable();
        file.put("watched", active != null);                 // true: a change of the endpoints restarts the application
        if (active != null) {
            WatchStatus watch = active.status();
            file.put("lastCheckedAt", watch.lastCheckedAt());
            file.put("restartRequested", watch.restartRequested());
            if (watch.lastError() != null) {
                file.put("lastError", watch.lastError());    // the newest version of the file was ignored; the workers run on
            }
        }
        health.withDetail("config", file);
        health.withDetail("dialogs", dialogs.details());      // mode, and in remote mode whether the desktop agent is connected
        return health.build();
    }
}
