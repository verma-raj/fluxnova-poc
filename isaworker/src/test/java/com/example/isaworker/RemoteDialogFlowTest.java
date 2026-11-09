package com.example.isaworker;

import com.example.isaworker.agent.DialogAgent;
import com.example.isaworker.gui.remote.DialogBroker;
import com.example.isaworker.persistence.WorkerDetail;
import com.example.isaworker.persistence.WorkerDetailRepository;
import com.example.isaworker.worker.WorkerHealthIndicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The dialogs of a worker that runs "in the container" appear on "the desktop": the worker runs in remote mode, the
 * {@link DialogAgent} (with a scripted person instead of Swing windows) fetches the dialogs over the worker's HTTP API, and
 * the process runs against {@link FakeFluxnova} and an in-memory database. Nothing is supplied as a process variable: every
 * answer has to travel through the bridge.
 */
@ExtendWith(OutputCaptureExtension.class)
class RemoteDialogFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String TOKEN = "test-token-1234";

    @TempDir
    Path directory;

    private FakeFluxnova engine;
    private ConfigurableApplicationContext context;
    private DialogAgent agent;
    private Thread agentThread;

    @BeforeEach
    void startFakeEngine() throws IOException {
        engine = new FakeFluxnova();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (agent != null) {
            agent.stop();
            agentThread.interrupt();
            agentThread.join(10_000);
        }
        if (context != null) {
            context.close();
        }
        engine.close();
    }

    private void startWorker(String... extraArgs) throws IOException {
        Path file = directory.resolve("fluxnova-engine.yml");
        Files.writeString(file, """
                fluxnova-workers:
                  tenants:
                    - id: "tenant-x"
                      baseUrl: "%s/tenant-x"
                """.formatted(engine.baseUrl()));
        String[] base = {"--FLUXNOVA_WORKERS_CONFIG=" + file.toString().replace('\\', '/'), "--server.port=0",
                "--spring.main.banner-mode=off", "--fluxnova-workers.async-response-timeout=1s",
                "--fluxnova-workers.default-username=admin", "--fluxnova-workers.default-password=secret",
                "--isaworker.gui.mode=remote", "--isaworker.gui.remote.token=" + TOKEN, "--isaworker.gui.remote.poll-wait=1s"};
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        context = new SpringApplicationBuilder(IsaWorkerApplication.class).run(args);
        context.getBean(WorkerDetailRepository.class).deleteAll();
    }

    private String workerUrl() {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    private void startAgent(ScriptedDialogUi desktop) {
        agent = new DialogAgent(workerUrl(), TOKEN, desktop, Duration.ofSeconds(1));
        agentThread = new Thread(agent::run, "dialog-agent");
        agentThread.setDaemon(true);
        agentThread.start();
    }

    /** What the health endpoint says about the dialogs: the mode, and whether the desktop agent is connected. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> dialogsHealth() {
        return (Map<String, Object>) context.getBean(WorkerHealthIndicator.class).health().getDetails().get("dialogs");
    }

    private WorkerDetail row(String processInstanceId) {
        return context.getBean(WorkerDetailRepository.class).findByTenantIdAndProcessInstanceId("tenant-x", processInstanceId).orElse(null);
    }

    private int status(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(workerUrl() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void theDialogsOpenOnTheDesktopAgentAndTheAnswersTravelBackIntoTheProcess() throws Exception {
        startWorker();
        ScriptedDialogUi desktop = new ScriptedDialogUi().answer("Rajiv Verma").answer("Bangalore");
        startAgent(desktop);

        // The process starts WITHOUT any answer: nothing is taken from process variables, the person is asked.
        engine.offerWorkerDetailsProcess("tenant-x", "42", null, null);

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(engine.completionOrder("tenant-x")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic"));

        // What the person was shown on the desktop, in order: two questions and the read-only summary.
        assertThat(desktop.prompts()).containsExactly("Please enter your name:", "Please enter your address:");
        assertThat(desktop.messages()).containsExactly("Name: Rajiv Verma\nAddress: Bangalore");
        assertThat(desktop.titles()).anySatisfy(t -> assertThat(t).contains("enter-name-topic").contains("process instance 42"));

        // What the workers did with the answers: they went to workerdb, not into the engine as process variables.
        WorkerDetail detail = row("42");
        assertThat(detail.getName()).isEqualTo("Rajiv Verma");
        assertThat(detail.getAddress()).isEqualTo("Bangalore");
        assertThat(engine.completion("tenant-x", "enter-name-topic").body()).doesNotContain("Rajiv Verma");          // no process variable: not stored in the engine's database
        assertThat(engine.completion("tenant-x", "enter-address-topic").body()).doesNotContain("Bangalore");
    }

    @Test
    void onlyWhenSwitchedOnDoTheAnswersAlsoBecomeProcessVariablesInTheEngine() throws Exception {
        startWorker("--isaworker.workers.set-process-variables=true");
        startAgent(new ScriptedDialogUi().answer("Rajiv Verma").answer("Bangalore"));

        engine.offerWorkerDetailsProcess("tenant-x", "43", null, null);

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(engine.completionOrder("tenant-x")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic"));
        assertThat(engine.completion("tenant-x", "enter-name-topic").body()).contains("\"name\"").contains("Rajiv Verma");
        assertThat(engine.completion("tenant-x", "enter-address-topic").body()).contains("\"address\"").contains("Bangalore");
        assertThat(row("43").getName()).isEqualTo("Rajiv Verma");                  // workerdb gets it either way
    }

    @Test
    void cancellingTheDialogOnTheDesktopSendsAFailureReportToTheEngine() throws Exception {
        startWorker("--isaworker.gui.retry-timeout=2s");
        startAgent(new ScriptedDialogUi().cancel());

        engine.offerWorkerDetailsProcess("tenant-x", "77", null, null);

        await().atMost(WAIT).untilAsserted(() -> assertThat(engine.failures()).hasSize(1));
        FakeFluxnova.Failure failure = engine.failures().get(0);
        assertThat(failure.topic()).isEqualTo("enter-name-topic");
        assertThat(failure.body()).contains("The name dialog was cancelled or closed without an answer")
                .contains("\"retries\":3").contains("\"retryTimeout\":2000");
        assertThat(engine.completions()).isEmpty();
        assertThat(row("77")).isNull();
    }

    @Test
    void withoutAnAgentTheTaskFailsAfterTheTimeoutWithAMessageThatSaysWhatToDo(CapturedOutput output) throws Exception {
        startWorker("--isaworker.gui.remote.answer-timeout=2s", "--isaworker.gui.retry-timeout=2s");

        engine.offerWorkerDetailsProcess("tenant-x", "88", null, null);

        await().atMost(WAIT).untilAsserted(() -> assertThat(engine.failures()).hasSize(1));
        assertThat(engine.failures().get(0).body()).contains("no dialog agent is connected").contains("run-dialog-agent.ps1");
        assertThat(output).contains("no dialog agent is connected");
        assertThat(engine.completions()).isEmpty();
    }

    @Test
    void anAgentThatStartsLaterStillGetsTheDialogThatWaits() throws Exception {
        startWorker();
        engine.offerWorkerDetailsProcess("tenant-x", "99", null, null);
        await().atMost(WAIT).untilAsserted(() -> assertThat(dialogsHealth()).containsEntry("dialogsWaiting", 1));

        ScriptedDialogUi desktop = new ScriptedDialogUi().answer("Asha Rao").answer("Mysuru");
        startAgent(desktop);                              // the person starts the agent after the process started

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(engine.completionOrder("tenant-x")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic"));
        assertThat(row("99").getName()).isEqualTo("Asha Rao");
    }

    @Test
    void anAgentThatDiedWithARequestOpenCannotMakeADialogVanish() throws Exception {
        startWorker();
        // The "dead agent": it has a long-poll request open at the worker, takes the dialog, and is never heard of again
        // (no receipt, no answer). It asks the broker directly, so that it certainly takes the first dialog.
        DialogBroker broker = context.getBean(DialogBroker.class);
        AtomicReference<DialogBroker.Request> taken = new AtomicReference<>();
        Thread dead = new Thread(() -> {
            try {
                taken.set(broker.next(Duration.ofSeconds(20)).orElse(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dead-agent");
        dead.setDaemon(true);
        dead.start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(dialogsHealth()).containsEntry("agentConnected", true));
        engine.offerWorkerDetailsProcess("tenant-x", "123", null, null);
        dead.join(WAIT.toMillis());                                                    // it took the first dialog ... and is gone
        assertThat(taken.get()).isNotNull();
        assertThat(taken.get().prompt()).isEqualTo("Please enter your name:");

        ScriptedDialogUi desktop = new ScriptedDialogUi().answer("Asha Rao").answer("Mysuru");
        startAgent(desktop);                              // a new agent: it must still get the dialog (after the receipt timeout)

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(engine.completionOrder("tenant-x")).containsExactly("enter-name-topic", "enter-address-topic", "show-details-topic"));
        assertThat(row("123").getName()).isEqualTo("Asha Rao");
        assertThat(desktop.prompts()).containsExactly("Please enter your name:", "Please enter your address:");
    }

    @Test
    void theHealthEndpointShowsWhetherTheDesktopAgentIsConnected() throws Exception {
        startWorker();
        assertThat(dialogsHealth()).containsEntry("mode", "remote").containsEntry("agentConnected", false);

        startAgent(new ScriptedDialogUi());

        await().atMost(WAIT).untilAsserted(() -> assertThat(dialogsHealth()).containsEntry("agentConnected", true));
    }

    @Test
    void theDialogApiRefusesCallsWithoutTheSharedSecret() throws Exception {
        startWorker();

        assertThat(status("GET", "/api/dialogs/next?wait=0", null, null)).isEqualTo(401);
        assertThat(status("GET", "/api/dialogs/next?wait=0", "Bearer wrong", null)).isEqualTo(401);
        assertThat(status("GET", "/api/dialogs/next?wait=0", TOKEN, null)).isEqualTo(401);               // not a Bearer header
        assertThat(status("POST", "/api/dialogs/x/answer", null, "{\"text\":\"hacked\"}")).isEqualTo(401);
        assertThat(status("POST", "/api/dialogs/x/cancel", "Bearer wrong", null)).isEqualTo(401);
        assertThat(status("POST", "/api/dialogs/x/ack", null, null)).isEqualTo(401);
        assertThat(status("POST", "/api/dialogs/x/received", null, null)).isEqualTo(401);
        assertThat(status("POST", "/api/dialogs/x/received", "Bearer wrong", null)).isEqualTo(401);

        assertThat(status("GET", "/api/dialogs/next?wait=0", "Bearer " + TOKEN, null)).isEqualTo(204);   // right secret: nothing to show
        assertThat(status("POST", "/api/dialogs/unknown/answer", "Bearer " + TOKEN, "{\"text\":\"x\"}")).isEqualTo(404);
        assertThat(status("POST", "/api/dialogs/unknown/cancel", "Bearer " + TOKEN, null)).isEqualTo(404);
        assertThat(status("POST", "/api/dialogs/unknown/received", "Bearer " + TOKEN, null)).isEqualTo(404);
    }

    @Test
    void remoteModeRefusesToStartWithoutTheSharedSecret() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(IsaWorkerApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off", "--FLUXNOVA_WORKERS_CONFIG=" + directory.resolve("none.yml").toString().replace('\\', '/'),
                "--isaworker.gui.mode=remote"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("isaworker.gui.remote.token").hasMessageContaining("ISAWORKER_DIALOG_TOKEN");
    }
}
