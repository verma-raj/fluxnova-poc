package com.example.isaworker.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Restarts the container by ending the application. The context is closed in an orderly way first (the workers' clients
 * are stopped, a request that is being handled finishes, the connection pool is closed), then the JVM exits. The
 * container then stops, and Docker's restart policy ({@code restart: unless-stopped}, see docker-compose.yml) starts the
 * same container again, which reads {@code fluxnova-engine.yml} from the start.
 * <p>
 * Without such a restart policy (an application started by hand) it simply ends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationExitRestart implements RestartAction {

    /** EX_TEMPFAIL, "temporary failure, try again": not zero, so even a restart policy of {@code on-failure} restarts it. */
    public static final int EXIT_CODE = 75;

    private final ConfigurableApplicationContext context;

    @Override
    public void restart(String reason) {
        log.warn("Restarting the application: {}", reason);
        // Not on the calling (scheduler) thread: closing the context stops that thread's own scheduler.
        Thread exit = new Thread(() -> System.exit(SpringApplication.exit(context, () -> EXIT_CODE)), "isaworker-restart");
        exit.start();
    }
}
