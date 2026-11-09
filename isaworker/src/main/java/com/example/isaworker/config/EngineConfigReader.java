package com.example.isaworker.config;

import com.example.isaworker.worker.MultiTenantWorkerOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reads {@code fluxnova-engine.yml} when the application starts, and starts one worker per engine endpoint it lists.
 * <p>
 * isaworker does not manage tenants. Which tenants exist, and when one is onboarded or offboarded (engine created or
 * removed, database created or dropped), is decided by the Fluxnova container ({@code fluxnova-multitenant}, which watches
 * its own {@code tenants.yml}). This file only tells the workers which engine endpoints to serve. The workers do not
 * follow later edits themselves: {@link EngineConfigWatcher} notices an edit and restarts the application, which then
 * comes back through here and reads the file afresh.
 * <p>
 * Whatever is wrong with the file, the application still starts, because an application that is down cannot report what is
 * wrong:
 * <ul>
 *   <li><b>Missing file</b>, empty file, or no tenant listed: idle mode (see {@link MultiTenantWorkerOrchestrator});</li>
 *   <li><b>Unreadable or invalid file</b> (YAML error, a value that cannot be bound): the error is logged and shown in
 *       {@code /actuator/health} ({@code config.error}), and the application starts idle.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EngineConfigReader implements ApplicationRunner {

    private final EngineConfigFileProperties properties;
    private final EngineConfigLoader loader;
    private final MultiTenantWorkerOrchestrator orchestrator;

    private volatile ConfigStatus status;
    private volatile TenantWorkerProperties startedWith;

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    /** Read-only view for the health endpoint. */
    public ConfigStatus status() {
        ConfigStatus current = status;
        return current != null ? current : new ConfigStatus(properties.getFile(), false, null, null, 0);
    }

    /**
     * The settings the workers were started with, or {@code null} while they are not started yet. {@link EngineConfigWatcher}
     * compares the file with this to see whether a restart is needed.
     */
    public TenantWorkerProperties startedWith() {
        return startedWith;
    }

    private void load() {
        String path = properties.getFile();
        Instant now = Instant.now();
        byte[] content = read(path);

        TenantWorkerProperties settings;
        String error = null;
        if (content == null) {
            settings = loader.baseline();                // what the jar and the environment say: normally no tenant, i.e. idle
        } else {
            try {
                settings = loader.parse(content);
                log.info("Loaded {}: {} tenant(s) listed", path, size(settings));
            } catch (EngineConfigException e) {
                error = e.getMessage();
                log.error("Ignoring invalid {}: {} Starting without tenants; fix the file and restart the container.", path, e.getMessage());
                settings = loader.baseline();
            }
        }
        status = new ConfigStatus(path, content != null, now, error, size(settings));
        startedWith = settings;
        orchestrator.start(settings);
    }

    /** @return the file content, or {@code null} if it is missing or cannot be read (reported in the log). */
    private static byte[] read(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (NoSuchFileException e) {
            log.info("{} does not exist: starting without tenants (idle mode).", path);
        } catch (IOException | RuntimeException e) {
            log.warn("{} cannot be read ({}): starting without tenants (idle mode).", path, e.toString());
        }
        return null;
    }

    private static int size(TenantWorkerProperties settings) {
        return settings.getTenants() == null ? 0 : settings.getTenants().size();
    }

    /**
     * What the reader found.
     *
     * @param loadedAt          when the file was read (start-up)
     * @param error             why the file was ignored, or {@code null}
     * @param tenantsConfigured number of tenant entries in the settings the workers were started with
     */
    public record ConfigStatus(String path, boolean fileFound, Instant loadedAt, String error, int tenantsConfigured) {
    }
}
