package com.example.isaworker.worker;

import com.example.isaworker.config.GuiProperties;
import com.example.isaworker.config.TenantWorkerProperties;
import com.example.isaworker.config.TenantWorkerProperties.Tenant;
import com.example.isaworker.config.WorkerProperties;
import com.example.isaworker.gui.DialogService;
import com.example.isaworker.persistence.WorkerDetailService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.ExternalTaskClient;
import org.finos.fluxnova.bpm.client.ExternalTaskClientBuilder;
import org.finos.fluxnova.bpm.client.interceptor.auth.BasicAuthProvider;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts one {@link ExternalTaskClient} per configured tenant, each polling that tenant's own engine for the topics of the
 * workers ({@link NameWorker}, {@link AddressWorker}, {@link EnterNameWorker}, {@link EnterAddressWorker},
 * {@link ShowDetailsWorker}), and stops them all when the application stops.
 * <p>
 * {@link #start} is called once, at start-up, with the settings read from {@code fluxnova-engine.yml} (see
 * {@code EngineConfigReader}). This class does <b>not</b> onboard or offboard tenants and never changes its workers in place:
 * which tenants exist is the business of the Fluxnova container ({@code fluxnova-multitenant}). When the file changes,
 * {@code EngineConfigWatcher} restarts the whole application, which then comes back through {@link #start}.
 * <p>
 * <b>Zero-tenant state.</b> With no tenant configured (empty list, or only unusable entries) this class logs a warning and
 * returns normally: the application keeps running in idle mode and its health endpoint stays UP.
 * <p>
 * <b>Isolation between tenants.</b> A tenant entry that is unusable (no id, no valid URL, duplicate id) is skipped with an
 * error in the log; it never prevents the other tenants from starting. Every tenant has its own client, polling thread,
 * credentials and back-off, so an engine that is down only affects its own worker.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiTenantWorkerOrchestrator {

    public static final String IDLE_MESSAGE = "No active Fluxnova engine configured. Application is running in idle mode.";

    private final GuiProperties gui;
    private final WorkerProperties workers;
    private final WorkerDetailService details;
    private final DialogService dialogs;

    private final EngineProbe probe = new EngineProbe();

    // All access is synchronized on this. Clients are only STOPPED outside the lock: stopping waits for the poll that is in
    // flight (up to the long-polling time), and the health endpoint, which also takes the lock, must not wait for that.
    private final Map<String, RunningTenant> running = new LinkedHashMap<>();
    private List<SkippedTenant> skipped = List.of();
    private boolean initialised;
    private boolean stopped;

    /**
     * Starts a worker for every usable tenant of {@code settings}. Meant to be called once, at start-up; a second call is
     * ignored. Never throws: whatever goes wrong, the application stays up with the workers that run.
     */
    public synchronized void start(TenantWorkerProperties settings) {
        if (stopped) {
            return;
        }
        if (initialised) {
            log.warn("The workers are already started. They are never changed in place; the application restarts to read a new tenant list.");
            return;
        }
        try {
            startAll(settings);
        } catch (RuntimeException e) {
            log.error("Unexpected error while starting the tenant workers; {} tenant worker(s) run", running.size(), e);
        }
        initialised = true;
    }

    private void startAll(TenantWorkerProperties settings) {
        List<Tenant> configured = settings.getTenants() == null ? List.of() : settings.getTenants();

        Map<String, Desired> desired = new LinkedHashMap<>();
        List<SkippedTenant> newSkipped = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            Tenant tenant = configured.get(i);
            String entry = describe(tenant, i + 1);
            String problem = null;
            Desired wanted = null;
            if (tenant == null) {
                problem = "the entry is empty";
            } else if (trimToNull(tenant.getId()) == null) {
                problem = "'id' is missing";
            } else if (desired.containsKey(tenant.getId().trim())) {
                problem = "the id is listed twice; only the first entry is used";
            } else {
                wanted = desire(settings, tenant);
                if (wanted == null) {
                    problem = "'baseUrl' is missing or is not an http(s) URL: " + tenant.getBaseUrl();
                }
            }
            if (wanted != null) {
                desired.put(wanted.tenantId(), wanted);
            } else {
                newSkipped.add(new SkippedTenant(entry, problem));
                log.error("Tenant {} is skipped: {}", entry, problem);
            }
        }

        // One client per tenant. A tenant that cannot be started is skipped; the others do not depend on it.
        for (Desired wanted : desired.values()) {
            try {
                running.put(wanted.tenantId(), startTenant(wanted));
            } catch (RuntimeException e) {
                newSkipped.add(new SkippedTenant("'" + wanted.tenantId() + "'", "could not be started: " + e));
                log.error("Tenant '{}' could not be started and is skipped; the other tenants are not affected",
                        wanted.tenantId(), e);
            }
        }
        skipped = List.copyOf(newSkipped);

        if (running.isEmpty()) {
            if (configured.isEmpty()) {
                log.warn(IDLE_MESSAGE);
            } else {
                log.warn("None of the {} configured tenant entries could be started. {}", configured.size(), IDLE_MESSAGE);
            }
        } else {
            log.info("Workers are running for {} tenant(s): {}", running.size(), running.keySet());
        }
    }

    /** @return the client settings of this tenant, or null if the entry has no usable URL */
    private static Desired desire(TenantWorkerProperties settings, Tenant tenant) {
        String id = tenant.getId().trim();
        String baseUrl = normaliseBaseUrl(tenant.getBaseUrl());
        if (baseUrl == null) {
            return null;
        }
        // A tenant that names its own user uses that user's password; otherwise the shared defaults apply.
        boolean ownCredentials = trimToNull(tenant.getUsername()) != null;
        String username = ownCredentials ? tenant.getUsername().trim() : trimToNull(settings.getDefaultUsername());
        String password = ownCredentials ? tenant.getPassword() : settings.getDefaultPassword();
        if (password != null && password.contains("${")) {
            log.warn("[{}] The password contains an unresolved placeholder ({}...): is the environment variable set in the "
                    + "container?", id, password.substring(0, password.indexOf("${") + 2));
        }
        return new Desired(id, new ClientSettings(baseUrl, username, password, settings.getWorkerIdPrefix(),
                settings.getLockDuration().toMillis(), settings.getMaxTasks(), settings.getAsyncResponseTimeout().toMillis(),
                settings.getDateFormat(), settings.getBackoffInitial(), settings.getBackoffMax(), settings.getBackoffFactor()));
    }

    private RunningTenant startTenant(Desired wanted) {
        String id = wanted.tenantId();
        ClientSettings s = wanted.settings();
        String workerId = s.workerIdPrefix() + "-" + id + "-" + hostName();

        ExternalTaskClientBuilder builder = ExternalTaskClient.create()
                .baseUrl(s.baseUrl())
                .workerId(workerId)
                .maxTasks(s.maxTasks())
                .dateFormat(s.dateFormat())
                .lockDuration(s.lockMillis())
                .asyncResponseTimeout(s.asyncResponseMillis())
                .backoffStrategy(new ErrorBackoffStrategy(s.backoffInitial(), s.backoffMax(), s.backoffFactor()));
        if (s.username() != null) {
            builder.addInterceptor(new BasicAuthProvider(s.username(), s.password() == null ? "" : s.password()));
        } else {
            log.warn("[{}] No username configured (fluxnova-workers.default-username / FLUXNOVA_REST_USER): requests to the "
                    + "engine are sent without credentials, which only works if its REST API is open.", id);
        }

        ExternalTaskClient client = builder.build();
        // The interactive topics wait for a person: a much longer lock than the machine-only topics, and a pause before the
        // engine offers a cancelled task again.
        long guiLockMillis = gui.getLockDuration().toMillis();
        long guiRetryMillis = gui.getRetryTimeout().toMillis();
        try {
            // The topics of the demo process (return-name, return-address), served for THIS tenant only: the handlers know
            // the tenant id.
            client.subscribe(NameWorker.TOPIC)
                    .lockDuration(s.lockMillis())
                    .handler(new NameWorker(id))
                    .open();
            client.subscribe(AddressWorker.TOPIC)
                    .lockDuration(s.lockMillis())
                    .variables(AddressWorker.INPUT_VARIABLE)      // only fetch what the handler reads
                    .handler(new AddressWorker(id))
                    .open();
            // The topics of the worker details process (enter_name -> enter_address -> show_details).
            client.subscribe(EnterNameWorker.TOPIC)
                    .lockDuration(guiLockMillis)
                    .variables(EnterNameWorker.VARIABLE)          // headless mode answers from this variable
                    .handler(new EnterNameWorker(id, details, dialogs, guiRetryMillis, workers.isSetProcessVariables()))
                    .open();
            client.subscribe(EnterAddressWorker.TOPIC)
                    .lockDuration(guiLockMillis)
                    .variables(EnterAddressWorker.VARIABLE)
                    .handler(new EnterAddressWorker(id, details, dialogs, guiRetryMillis, workers.isSetProcessVariables()))
                    .open();
            client.subscribe(ShowDetailsWorker.TOPIC)
                    .lockDuration(guiLockMillis)
                    .variables()                                  // reads workerdb, needs no process variable
                    .handler(new ShowDetailsWorker(id, details, dialogs, guiRetryMillis))
                    .open();
        } catch (RuntimeException e) {
            stopQuietly(id, client);
            throw e;
        }

        List<String> topics = List.of(NameWorker.TOPIC, AddressWorker.TOPIC, EnterNameWorker.TOPIC, EnterAddressWorker.TOPIC,
                ShowDetailsWorker.TOPIC);
        log.info("[{}] Worker started: polling {} as '{}' for topics {}", id, s.baseUrl(), workerId, topics);
        probe.check(id, s.baseUrl(), s.username(), s.password());
        return new RunningTenant(id, s.baseUrl(), workerId, topics, client);
    }

    /** Stops every client, so that locked tasks are not left half-way and no polling thread keeps the JVM busy. */
    @PreDestroy
    public void stop() {
        List<RunningTenant> toStop;
        synchronized (this) {
            stopped = true;                 // a configuration that is applied during shutdown must not start new clients
            toStop = new ArrayList<>(running.values());
            running.clear();
        }
        toStop.forEach(tenant -> stopQuietly(tenant.tenantId(), tenant.client()));
        if (!toStop.isEmpty()) {
            log.info("Stopped the workers of {} tenant(s)", toStop.size());
        }
    }

    /** Snapshot for the health endpoint. */
    public synchronized Status status() {
        List<TenantStatus> tenants = running.values().stream()
                .map(t -> new TenantStatus(t.tenantId(), t.baseUrl(), t.workerId(), t.topics(), t.client().isActive()))
                .toList();
        return new Status(initialised, tenants, skipped);
    }

    private static void stopQuietly(String tenantId, ExternalTaskClient client) {
        try {
            client.stop();
            log.info("[{}] Worker stopped", tenantId);
        } catch (RuntimeException e) {
            log.warn("[{}] Error while stopping the client: {}", tenantId, e.toString());
        }
    }

    private static String describe(Tenant tenant, int position) {
        String id = tenant == null ? null : trimToNull(tenant.getId());
        return id != null ? "'" + id + "'" : "#" + position;
    }

    /** @return the URL without trailing slashes, or null if it is not an absolute http(s) URL with a host */
    static String normaliseBaseUrl(String url) {
        String trimmed = trimToNull(url);
        if (trimmed == null) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return trimmed.replaceAll("/+$", "");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            return "unknown-host";
        }
    }

    /** Everything that decides how a tenant's client behaves. The password never appears in {@link #toString()}. */
    private record ClientSettings(String baseUrl, String username, String password, String workerIdPrefix, long lockMillis,
                                  int maxTasks, long asyncResponseMillis, String dateFormat, Duration backoffInitial,
                                  Duration backoffMax, double backoffFactor) {
        @Override
        public String toString() {
            return "ClientSettings[baseUrl=" + baseUrl + ", username=" + username + ", password=<hidden>]";
        }
    }

    private record Desired(String tenantId, ClientSettings settings) {
    }

    private record RunningTenant(String tenantId, String baseUrl, String workerId, List<String> topics,
                                 ExternalTaskClient client) {
    }

    /** A tenant entry of the configuration that was not started, and why. */
    public record SkippedTenant(String entry, String reason) {
    }

    /** A running tenant worker. {@code polling} is false if the client's polling thread is not active. */
    public record TenantStatus(String tenantId, String baseUrl, String workerId, List<String> topics, boolean polling) {
    }

    /** What the orchestrator is doing; {@link #idle()} when no tenant worker runs. */
    public record Status(boolean initialised, List<TenantStatus> tenants, List<SkippedTenant> skipped) {
        public boolean idle() {
            return tenants.isEmpty();
        }
    }
}
