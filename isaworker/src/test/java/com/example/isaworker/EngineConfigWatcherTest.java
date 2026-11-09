package com.example.isaworker;

import com.example.isaworker.config.EngineConfigWatcher;
import com.example.isaworker.config.RestartAction;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The watcher of {@code fluxnova-engine.yml}: the real application runs against {@link FakeFluxnova}, the file is edited
 * for real, and the restart is recorded instead of carried out (a test must not end its own JVM; the real restart is
 * {@code ApplicationExitRestart}, checked in a container).
 */
@ExtendWith(OutputCaptureExtension.class)
class EngineConfigWatcherTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path directory;

    private final List<String> restarts = new CopyOnWriteArrayList<>();
    private FakeFluxnova engine;
    private ConfigurableApplicationContext context;
    private Path file;

    @BeforeEach
    void startFakeEngine() throws IOException {
        engine = new FakeFluxnova();
        file = directory.resolve("fluxnova-engine.yml");
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        engine.close();
    }

    private String tenants(String... ids) {
        StringBuilder yaml = new StringBuilder("fluxnova-workers:\n  tenants:\n");
        for (String id : ids) {
            yaml.append("    - id: \"").append(id).append("\"\n      baseUrl: \"").append(engine.baseUrl()).append('/').append(id).append("\"\n");
        }
        return yaml.toString();
    }

    /** Starts the application with the file watched every 200 ms and the restart recorded. */
    private void start(String yaml) throws IOException {
        if (yaml != null) {
            Files.writeString(file, yaml);
        }
        context = new SpringApplicationBuilder(IsaWorkerApplication.class)
                .initializers(applicationContext -> ((GenericApplicationContext) applicationContext).registerBean(
                        "recordingRestart", RestartAction.class, () -> restarts::add, definition -> definition.setPrimary(true)))
                .run("--FLUXNOVA_WORKERS_CONFIG=" + file.toString().replace('\\', '/'), "--server.port=0",
                        "--spring.main.banner-mode=off", "--fluxnova-workers.async-response-timeout=1s",
                        "--fluxnova-workers.config.restart-on-change=true", "--fluxnova-workers.config.poll-interval=200ms");
        MultiTenantWorkerOrchestrator orchestrator = context.getBean(MultiTenantWorkerOrchestrator.class);
        await().atMost(WAIT).until(() -> orchestrator.status().initialised());
    }

    private EngineConfigWatcher watcher() {
        return context.getBean(EngineConfigWatcher.class);
    }

    private List<String> servedTenants() {
        return context.getBean(MultiTenantWorkerOrchestrator.class).status().tenants().stream()
                .map(MultiTenantWorkerOrchestrator.TenantStatus::tenantId).toList();
    }

    @Test
    void addingATenantToTheFileRequestsExactlyOneRestart_andTheRunningWorkersAreNotTouchedInPlace() throws IOException {
        start(tenants("tenant-x"));
        assertThat(servedTenants()).containsExactly("tenant-x");

        Files.writeString(file, tenants("tenant-x", "tenant-y"));

        await().atMost(WAIT).until(() -> !restarts.isEmpty());
        assertThat(restarts).hasSize(1);
        assertThat(restarts.get(0)).contains("tenant-x").contains("tenant-y").contains(file.getFileName().toString());
        assertThat(watcher().status().restartRequested()).isTrue();
        // The application is only asked to restart. It does not change its workers itself: that is the restart's job.
        assertThat(servedTenants()).containsExactly("tenant-x");
        // And it asks once, not on every look while the restart is under way.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> restarts.size() == 1);
    }

    @Test
    void removingATenantOrChangingItsEndpointAlsoRestarts() throws IOException {
        start(tenants("tenant-x", "tenant-y"));

        Files.writeString(file, tenants("tenant-x"));
        await().atMost(WAIT).until(() -> restarts.size() == 1);
        assertThat(restarts.get(0)).contains("tenant-x, tenant-y").contains("now [tenant-x]");
    }

    @Test
    void aTenantAddedToAnIdleApplicationRestartsIt_evenIfThereWasNoFileAtStartUp(CapturedOutput output) throws IOException {
        start(null);                                           // no file at all: idle
        assertThat(servedTenants()).isEmpty();
        assertThat(output).contains("No active Fluxnova engine configured. Application is running in idle mode.");

        Files.writeString(file, tenants("tenant-x"));

        await().atMost(WAIT).until(() -> restarts.size() == 1);
        assertThat(restarts.get(0)).contains("now [tenant-x]");
    }

    @Test
    void anEditThatChangesNoSetting_commentsLayoutOrderOfTheKeys_restartsNothing(CapturedOutput output) throws IOException {
        start(tenants("tenant-x"));

        Files.writeString(file, "# a comment that was not there before\n\nfluxnova-workers:\n  tenants:\n    - baseUrl: \""
                + engine.baseUrl() + "/tenant-x\"\n      id: \"tenant-x\"      # keys in the other order\n");

        // The change is seen, judged and found to be nothing: the log says so once, and nothing is restarted.
        await().atMost(WAIT).untilAsserted(() -> assertThat(output).contains("changed, but the engine endpoints and settings are the same"));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(restarts::isEmpty);
        assertThat(servedTenants()).containsExactly("tenant-x");
        assertThat(watcher().status().restartRequested()).isFalse();
    }

    @Test
    void anInvalidFileIsReportedAndIgnored_andFixingItRestarts(CapturedOutput output) throws IOException {
        start(tenants("tenant-x"));

        Files.writeString(file, "fluxnova-workers:\n  tenants:\n   - id: \"tenant-y\"\n      baseUrl: \"http://h/e\"\n");   // bad indent

        await().atMost(WAIT).until(() -> watcher().status().lastError() != null);
        assertThat(output).contains("Ignoring invalid");
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(restarts::isEmpty);
        assertThat(servedTenants()).containsExactly("tenant-x");                 // the workers run on, untouched

        Files.writeString(file, tenants("tenant-x", "tenant-y"));                  // the mistake is fixed

        await().atMost(WAIT).until(() -> restarts.size() == 1);
        assertThat(restarts.get(0)).contains("tenant-y");
        assertThat(watcher().status().lastError()).isNull();
    }

    @Test
    void aFileThatKeepsChangingIsNotActedOnUntilItHasSettled() throws IOException, InterruptedException {
        start(tenants("tenant-x"));

        // A file that is being written: a different content every 60 ms, much faster than the 200 ms look.
        for (int i = 1; i <= 20; i++) {
            Files.writeString(file, tenants("tenant-x", "tenant-w" + i));
            Thread.sleep(60);
        }
        assertThat(restarts).as("nothing is acted on while the file is still changing").isEmpty();

        await().atMost(WAIT).until(() -> !restarts.isEmpty());
        assertThat(restarts).hasSize(1);
        assertThat(restarts.get(0)).contains("tenant-w20");                      // the settled version, not an earlier one
    }

    @Test
    void aFileThatDisappearsChangesNothing(CapturedOutput output) throws IOException {
        start(tenants("tenant-x"));

        Files.delete(file);

        await().atMost(WAIT).untilAsserted(() -> assertThat(output).contains("does not exist; the workers keep running"));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(restarts::isEmpty);
        assertThat(servedTenants()).containsExactly("tenant-x");
    }

    @Test
    void anUnchangedFileIsNeverRestartedForNoReason() throws IOException {
        start(tenants("tenant-x", "tenant-y"));

        // Many looks at the unchanged file (200 ms apart): the settings the application started with equal the file's.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).until(restarts::isEmpty);
        assertThat(watcher().status().lastCheckedAt()).isNotNull();
    }
}
