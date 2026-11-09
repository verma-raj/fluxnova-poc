package com.example.isaworker;

import com.example.isaworker.config.EngineConfigReader;
import com.example.isaworker.worker.MultiTenantWorkerOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real application and the real external task clients against {@link FakeFluxnova}, a small fake of the Fluxnova
 * REST API on a random port, with the tenant list in a real file that the application reads.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantWorkersIntegrationTest {

    @TempDir
    Path directory;

    private FakeFluxnova engine;
    private ConfigurableApplicationContext context;

    @BeforeEach
    void startFakeEngine() throws IOException {
        engine = new FakeFluxnova();
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        engine.close();
    }

    private ConfigurableApplicationContext start(String yaml, String... extraArgs) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, yaml);
        String[] base = {"--FLUXNOVA_WORKERS_CONFIG=" + file.toString().replace('\\', '/'), "--server.port=0",
                "--spring.main.banner-mode=off", "--fluxnova-workers.async-response-timeout=1s"};
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        return new SpringApplicationBuilder(IsaWorkerApplication.class).run(args);
    }

    @Test
    void everyConfiguredTenantGetsItsOwnWorker_theDemoProcessRunsAndEachTenantIsServedWithItsOwnCredentials(CapturedOutput output)
            throws IOException {
        engine.offerNameTask("tenant-x");
        engine.offerNameTask("tenant-y");

        context = start("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%1$s/tenant-x/"
                    - id: "tenant-y"
                      baseUrl: "%1$s/tenant-y"
                      username: "own-user"
                      password: "own-pass"
                    - id: "tenant-x"
                      baseUrl: "%1$s/tenant-x"
                    - id: "broken"
                """.formatted(engine.baseUrl()), "--fluxnova-workers.default-username=shared-user",
                "--fluxnova-workers.default-password=shared-pass");

        // Both tasks of the demo process were fetched, executed and completed for BOTH tenants.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(engine.completions()).hasSize(4));

        for (String tenant : List.of("tenant-x", "tenant-y")) {
            FakeFluxnova.Completion name = engine.completion(tenant, "return-name");
            assertThat(name.body()).contains("\"customerName\"").contains("Rajiv Verma");
            FakeFluxnova.Completion address = engine.completion(tenant, "return-address");
            assertThat(address.body()).contains("\"customerAddress\"").contains("Bangalore");
        }
        // The name task of each tenant ran before its address task (the fake only offers the address task afterwards).
        assertThat(engine.completionOrder("tenant-x")).containsExactly("return-name", "return-address");

        // Credentials: tenant-x uses the shared default, tenant-y its own. Passwords never reach the log.
        assertThat(engine.completion("tenant-x", "return-name").authorization()).isEqualTo(basic("shared-user", "shared-pass"));
        assertThat(engine.completion("tenant-y", "return-name").authorization()).isEqualTo(basic("own-user", "own-pass"));
        assertThat(output).doesNotContain("shared-pass").doesNotContain("own-pass");

        // The handlers logged the tenant they ran for, and the address worker read the name set by the name worker.
        assertThat(output).contains("Executing 'return-name' for tenant tenant-x")
                .contains("Executing 'return-name' for tenant tenant-y")
                .contains("Executing 'return-address' for tenant tenant-x")
                .contains("customer is \"Rajiv Verma\"");

        // The fetch request names the worker after the tenant; the address topic only asks for the variable it reads.
        assertThat(engine.fetchBodies("tenant-x")).anySatisfy(body -> assertThat(body)
                .contains("isaworker-tenant-x-").contains("return-name").contains("return-address").contains("customerName"));

        // Bad entries were skipped, did not stop the good ones, and are visible in the health details.
        MultiTenantWorkerOrchestrator.Status status = context.getBean(MultiTenantWorkerOrchestrator.class).status();
        assertThat(status.tenants()).extracting(MultiTenantWorkerOrchestrator.TenantStatus::tenantId)
                .containsExactly("tenant-x", "tenant-y");
        assertThat(status.tenants()).allSatisfy(t -> assertThat(t.polling()).isTrue());
        assertThat(status.skipped()).hasSize(2);
        assertThat(output).contains("the id is listed twice").contains("'baseUrl' is missing");
        assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void anEngineThatIsNotReachableDoesNotStopTheApplication_andIsReportedOnce(CapturedOutput output) throws IOException {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        context = start("""
                fluxnova-workers:
                  tenants:
                    - id: "offline"
                      baseUrl: "http://localhost:%d/engine-rest/engine/offline"
                """.formatted(closedPort), "--fluxnova-workers.backoff-initial=100ms", "--fluxnova-workers.backoff-max=200ms");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(output).contains("Fluxnova engine at http://localhost:" + closedPort + "/engine-rest/engine/offline is not reachable yet"));
        assertThat(context.isRunning()).isTrue();
        assertThat(context.getBean(MultiTenantWorkerOrchestrator.class).status().tenants()).hasSize(1);
        assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void wrongCredentialsAreExplainedInTheLog(CapturedOutput output) throws IOException {
        engine.rejectCredentials();
        context = start("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%s/tenant-x"
                """.formatted(engine.baseUrl()), "--fluxnova-workers.default-username=u", "--fluxnova-workers.default-password=p");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(output).contains("rejected the credentials (HTTP 401)"));
        assertThat(engine.completions()).isEmpty();
    }

    /**
     * isaworker does not onboard or offboard tenants: that is the Fluxnova container's job, and the running workers are
     * never changed in place. With restart-on-change switched off (the setting of every test, so that a test never ends its
     * own JVM) the file is only read at start-up: a later edit (tenant added, tenant removed) changes nothing. With it
     * switched on, an edit restarts the application: see {@link EngineConfigWatcherTest}.
     */
    @Test
    void withRestartOnChangeSwitchedOff_editingTheFileWhileTheApplicationRunsChangesNothing(CapturedOutput output)
            throws IOException {
        context = start("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%s/tenant-x"
                """.formatted(engine.baseUrl()), "--fluxnova-workers.default-username=u", "--fluxnova-workers.default-password=p");
        MultiTenantWorkerOrchestrator orchestrator = context.getBean(MultiTenantWorkerOrchestrator.class);
        assertThat(orchestrator.status().tenants()).extracting(MultiTenantWorkerOrchestrator.TenantStatus::tenantId)
                .containsExactly("tenant-x");
        EngineConfigReader.ConfigStatus atStartUp = context.getBean(EngineConfigReader.class).status();
        assertThat(atStartUp.fileFound()).isTrue();
        assertThat(atStartUp.tenantsConfigured()).isEqualTo(1);
        assertThat(atStartUp.error()).isNull();

        // Another tenant is added and tenant-x is removed in the file ...
        Files.writeString(directory.resolve("fluxnova-engine.yml"), """
                fluxnova-workers:
                  tenants:
                    - id: "tenant-y"
                      baseUrl: "%s/tenant-y"
                """.formatted(engine.baseUrl()));

        // ... and nothing notices: same worker, no new worker, no log line about a new version of the file.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(orchestrator.status().tenants()).extracting(MultiTenantWorkerOrchestrator.TenantStatus::tenantId)
                    .containsExactly("tenant-x");
            assertThat(orchestrator.status().tenants()).allSatisfy(t -> assertThat(t.polling()).isTrue());
        });
        assertThat(output).doesNotContain("Detected a new version").doesNotContain("tenant-y");
        assertThat(context.getBean(EngineConfigReader.class).status()).isEqualTo(atStartUp);
        assertThat(engine.fetchBodies("tenant-y")).isEmpty();
    }

    @Test
    void stoppingTheApplicationStopsEveryClient() throws IOException {
        context = start("""
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%s/tenant-x"
                """.formatted(engine.baseUrl()), "--fluxnova-workers.default-username=u", "--fluxnova-workers.default-password=p");
        MultiTenantWorkerOrchestrator orchestrator = context.getBean(MultiTenantWorkerOrchestrator.class);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(orchestrator.status().tenants()).hasSize(1));

        context.close();

        assertThat(orchestrator.status().tenants()).isEmpty();
        long pollsAfterStop = engine.fetchCount();
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(engine.fetchCount()).isLessThanOrEqualTo(pollsAfterStop + 1));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
