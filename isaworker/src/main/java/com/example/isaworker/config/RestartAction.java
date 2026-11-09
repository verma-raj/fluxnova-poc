package com.example.isaworker.config;

/**
 * What {@link EngineConfigWatcher} does when the endpoint file changed: restart the application. A separate interface so
 * that a test can record the request instead of ending its own JVM.
 */
@FunctionalInterface
public interface RestartAction {

    /**
     * Starts the restart and returns at once.
     *
     * @param reason what changed, for the log
     */
    void restart(String reason);
}
