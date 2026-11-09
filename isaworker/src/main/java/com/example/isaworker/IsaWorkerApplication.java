package com.example.isaworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;
import java.util.function.Function;

/**
 * External task workers for a multi-tenant Fluxnova server, with their state in PostgreSQL ({@code workerdb}).
 * <p>
 * The Fluxnova server hosts one process engine per tenant. This application talks to those engines only over their REST
 * API: for every engine endpoint listed in the external {@code fluxnova-engine.yml} it opens one external task client that
 * long-polls that tenant's engine for the topics of the workers. BPMN models are not deployed from here.
 * <p>
 * The file is read when the application starts ({@link com.example.isaworker.config.EngineConfigReader}) and watched while
 * it runs: when the endpoints in it change, {@link com.example.isaworker.config.EngineConfigWatcher} restarts the
 * application (the container's restart policy brings it back) so that it reads them afresh. Onboarding and offboarding of
 * tenants is not done here: that is the job of the Fluxnova container ({@code fluxnova-multitenant}). With no tenant listed
 * (or no file at all) the application starts anyway and idles, see
 * {@link com.example.isaworker.worker.MultiTenantWorkerOrchestrator}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IsaWorkerApplication {

    public static void main(String[] args) {
        useUtc();
        allowDesktopDialogs();
        SpringApplication.run(IsaWorkerApplication.class, args);
    }

    /**
     * Runs the JVM in UTC. The PostgreSQL JDBC driver announces the JVM's default time zone on every connection; a developer
     * machine can report a legacy alias such as {@code Asia/Calcutta}, and current PostgreSQL images do not know those aliases
     * and refuse the connection ({@code invalid value for parameter "TimeZone"}).
     */
    static void useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Spring Boot switches the JVM to headless mode by default ({@code java.awt.headless=true}), which would make every Swing
     * dialog fail with a HeadlessException even on a desktop. Unless the property was set explicitly (for example with
     * {@code -Djava.awt.headless=...}), it is set here from what the machine really has.
     */
    static void allowDesktopDialogs() {
        if (System.getProperty("java.awt.headless") == null) {
            boolean desktop = hasDesktop(System.getProperty("os.name", ""), System::getenv);
            System.setProperty("java.awt.headless", Boolean.toString(!desktop));
        }
    }

    /** Windows and macOS always have a window system for an interactive session; on Linux there must be a display server. */
    static boolean hasDesktop(String osName, Function<String, String> environment) {
        String os = osName.toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        return notBlank(environment.apply("DISPLAY")) || notBlank(environment.apply("WAYLAND_DISPLAY"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
