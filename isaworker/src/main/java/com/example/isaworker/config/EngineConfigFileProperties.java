package com.example.isaworker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the file with the Fluxnova engine endpoints is and how it is watched ({@code fluxnova-workers.config.*}).
 * These settings are deliberately NOT part of {@link TenantWorkerProperties}: they say where to find the file, so the file
 * itself cannot change them.
 */
@Data
@ConfigurationProperties(prefix = "fluxnova-workers.config")
public class EngineConfigFileProperties {

    /** The external endpoint configuration, normally a mounted volume. Environment variable: FLUXNOVA_WORKERS_CONFIG. */
    private String file = "/config/fluxnova-engine.yml";

    /**
     * How often the file is looked at. A change is acted on once two consecutive looks see the same content, so a restart
     * follows an edit after one to two intervals. Polling (not file-system events) because the folder is normally a bind
     * mount from another operating system, which does not deliver change events into the container.
     */
    private Duration pollInterval = Duration.ofSeconds(5);

    /**
     * {@code true} (default): when the endpoints or settings in the file change, the application stops itself, and the
     * container's restart policy ({@code restart: unless-stopped}) starts it again, so that it reads the file afresh. Switch
     * it off ({@code ISAWORKER_RESTART_ON_CHANGE=false}) for a run without such a policy, for example on a desktop, where the
     * application would stop and stay stopped. Environment variable: ISAWORKER_RESTART_ON_CHANGE.
     */
    private boolean restartOnChange = true;
}
