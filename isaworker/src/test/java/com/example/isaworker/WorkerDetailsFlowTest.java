package com.example.isaworker;

import com.example.isaworker.persistence.WorkerDetail;
import com.example.isaworker.persistence.WorkerDetailRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The worker details process end to end: the real application and the real external task clients against
 * {@link FakeFluxnova}, with the tenant list in a real file and the state in a real (in-memory) database. The dialogs run in
 * headless mode, so the "typed" answers are the process variables the process was started with.
 */
@ExtendWith(OutputCaptureExtension.class)
class WorkerDetailsFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

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

    private void start(String... extraArgs) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, """
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%1$s/tenant-x"
                    - id: "tenant-y"
                      baseUrl: "%1$s/tenant-y"
                """.formatted(engine.baseUrl()));
        String[] base = {"--FLUXNOVA_WORKERS_CONFIG=" + file.toString().replace('\\', '/'), "--server.port=0",
                "--spring.main.banner-mode=off", "--fluxnova-workers.async-response-timeout=1s",
                "--fluxnova-workers.default-username=admin", "--fluxnova-workers.default-password=secret"};
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        context = new SpringApplicationBuilder(IsaWorkerApplication.class).run(args);
        context.getBean(WorkerDetailRepository.class).deleteAll();         // the in-memory database outlives a test
    }

    private WorkerDetail row(String tenant, String processInstanceId) {
        return context.getBean(WorkerDetailRepository.class).findByTenantIdAndProcessInstanceId(tenant, processInstanceId).orElse(null);
    }

    @Test
    void enterName_enterAddress_showDetails_runForEachTenantAndShareTheirStateThroughTheDatabase(CapturedOutput output)
            throws IOException {
        // The SAME process instance id in two tenants: each tenant's engine numbers its instances on its own.
        engine.offerWorkerDetailsProcess("tenant-x", "42", "Rajiv Verma", "Bangalore");
        engine.offerWorkerDetailsProcess("tenant-y", "42", "Asha Rao", "Mysuru");
        start();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(engine.completionOrder("tenant-x")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic");
            assertThat(engine.completionOrder("tenant-y")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic");
        });

        // workerdb: one row per tenant, each with its own name and address (the shared instance id did not mix them up).
        WorkerDetail x = row("tenant-x", "42");
        assertThat(x.getName()).isEqualTo("Rajiv Verma");
        assertThat(x.getAddress()).isEqualTo("Bangalore");
        WorkerDetail y = row("tenant-y", "42");
        assertThat(y.getName()).isEqualTo("Asha Rao");
        assertThat(y.getAddress()).isEqualTo("Mysuru");
        assertThat(context.getBean(WorkerDetailRepository.class).count()).isEqualTo(2);

        // By default the workers set NO process variables: name and address are business data and live in workerdb only.
        assertThat(engine.completion("tenant-x", "enter-name-topic").body()).doesNotContain("Rajiv Verma");          // no process variable: not stored in the engine's database
        assertThat(engine.completion("tenant-x", "enter-address-topic").body()).doesNotContain("Bangalore");
        assertThat(engine.completion("tenant-x", "show-details-topic").body()).doesNotContain("Rajiv Verma").doesNotContain("Bangalore");

        // show_details displayed what it READ FROM THE DATABASE (headless mode writes it to the log).
        assertThat(output).contains("Details of process instance 42 read from workerdb: name=\"Rajiv Verma\", address=\"Bangalore\"")
                .contains("Details of process instance 42 read from workerdb: name=\"Asha Rao\", address=\"Mysuru\"");

        // The interactive topics are locked for 15 minutes (a person types slowly), the machine-only ones for 30 s.
        assertThat(engine.fetchBodies("tenant-x")).anySatisfy(body -> assertThat(body)
                .contains("enter-name-topic").contains("enter-address-topic").contains("show-details-topic").contains("900000"));
        assertThat(context.getBean(HealthEndpoint.class).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aCancelledDialogSendsAFailureReportToTheEngine_nothingIsWritten_andTheProcessDoesNotMoveOn(CapturedOutput output)
            throws IOException {
        // No answer supplied and no desktop: the same situation as a person pressing Cancel.
        engine.offerWorkerDetailsProcess("tenant-x", "77", null, null);
        start("--isaworker.gui.retry-timeout=2s");

        await().atMost(WAIT).untilAsserted(() -> assertThat(engine.failures()).hasSize(1));

        FakeFluxnova.Failure failure = engine.failures().get(0);
        assertThat(failure.tenant()).isEqualTo("tenant-x");
        assertThat(failure.topic()).isEqualTo("enter-name-topic");
        assertThat(failure.body()).contains("The name dialog was cancelled or closed without an answer")
                .contains("\"retries\":3").contains("\"retryTimeout\":2000");
        assertThat(engine.completions()).isEmpty();                    // the task was not completed: the process stays at enter_name
        assertThat(row("tenant-x", "77")).isNull();                    // and nothing was persisted
        assertThat(output).contains("reported as failed to the engine");
        assertThat(context.getBean(MultiTenantWorkerOrchestrator.class).status().tenants()).hasSize(2);   // the worker keeps serving
    }
}
