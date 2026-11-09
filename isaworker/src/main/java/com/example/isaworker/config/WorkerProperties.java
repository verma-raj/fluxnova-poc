package com.example.isaworker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Behaviour of the three interactive workers ({@code isaworker.workers.*}). */
@Data
@ConfigurationProperties(prefix = "isaworker.workers")
public class WorkerProperties {

    /**
     * Also set the answers as process variables {@code name} and {@code address} when a task is completed.
     * <p>
     * <b>Off by default.</b> A process variable is stored by the engine, in the engine's own database. Fluxnova's database is
     * for Fluxnova's use only; the business data (the name and the address) belongs in {@code workerdb}, where the workers
     * write it anyway. Switch this on only if something in the process itself, a gateway or an expression, needs the values.
     */
    private boolean setProcessVariables = false;
}
