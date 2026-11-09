package com.example.isaworker.agent;

import com.example.isaworker.ScriptedDialogUi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The desktop agent against a scripted worker: what it asks, shows and reports. */
class DialogAgentTest {

    private HttpServer worker;
    /** What the scripted worker answers to GET /api/dialogs/next, one entry per call; 204 when empty. */
    private final ConcurrentLinkedQueue<String> nextResponses = new ConcurrentLinkedQueue<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusOfReports = new AtomicInteger(204);
    private final AtomicInteger statusOfReceipt = new AtomicInteger(204);
    private volatile int statusOfNext = 0;

    @BeforeEach
    void startScriptedWorker() throws IOException {
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/api/dialogs", this::handle);
        worker.start();
    }

    @AfterEach
    void stop() {
        worker.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (body.isEmpty() ? "" : " " + body));
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (exchange.getRequestURI().getPath().equals("/api/dialogs/next")) {
                String json = nextResponses.poll();
                if (statusOfNext != 0) {
                    exchange.sendResponseHeaders(statusOfNext, -1);
                } else if (json == null) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } else {
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/received") ? statusOfReceipt.get() : statusOfReports.get(), -1);
            }
        }
    }

    private DialogAgent agent(ScriptedDialogUi ui) {
        return new DialogAgent("http://127.0.0.1:" + worker.getAddress().getPort() + "/", "s3cret", ui, Duration.ofSeconds(1));
    }

    @Test
    void nothingToShowIsNotAnError() throws Exception {
        assertThat(agent(new ScriptedDialogUi()).pollOnce()).isFalse();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)).startsWith("GET /api/dialogs/next");
        assertThat(authorizations).containsExactly("Bearer s3cret");        // the shared secret goes with every call
    }

    @Test
    void aQuestionIsShownAndTheAnswerIsSentBack() throws Exception {
        nextResponses.add("{\"id\":\"d1\",\"kind\":\"INPUT\",\"title\":\"enter-name-topic (process instance 9)\","
                + "\"prompt\":\"Please enter your name:\",\"text\":null,\"somethingNew\":1}");       // unknown fields are ignored
        ScriptedDialogUi ui = new ScriptedDialogUi().answer("Rajiv Verma");

        assertThat(agent(ui).pollOnce()).isTrue();

        assertThat(ui.titles()).containsExactly("enter-name-topic (process instance 9)");
        assertThat(ui.prompts()).containsExactly("Please enter your name:");
        assertThat(calls.get(1)).isEqualTo("POST /api/dialogs/d1/received");         // confirmed BEFORE the window is shown
        assertThat(calls.get(2)).isEqualTo("POST /api/dialogs/d1/answer {\"text\":\"Rajiv Verma\"}");
    }

    @Test
    void aCancelledQuestionIsReportedAsCancelled() throws Exception {
        nextResponses.add("{\"id\":\"d2\",\"kind\":\"INPUT\",\"title\":\"t\",\"prompt\":\"p\",\"text\":null}");

        assertThat(agent(new ScriptedDialogUi().cancel()).pollOnce()).isTrue();

        assertThat(calls.get(2)).isEqualTo("POST /api/dialogs/d2/cancel");
    }

    @Test
    void aReadOnlyMessageIsShownAndAcknowledged() throws Exception {
        nextResponses.add("{\"id\":\"d3\",\"kind\":\"MESSAGE\",\"title\":\"Worker details\",\"prompt\":null,"
                + "\"text\":\"Name: Rajiv Verma\\nAddress: Bangalore\"}");
        ScriptedDialogUi ui = new ScriptedDialogUi();

        assertThat(agent(ui).pollOnce()).isTrue();

        assertThat(ui.messages()).containsExactly("Name: Rajiv Verma\nAddress: Bangalore");
        assertThat(calls.get(2)).isEqualTo("POST /api/dialogs/d3/ack");
    }

    @Test
    void aWindowThatCannotBeShownIsReportedAsCancelledSoTheWorkerDoesNotWait() throws Exception {
        nextResponses.add("{\"id\":\"d4\",\"kind\":\"INPUT\",\"title\":\"t\",\"prompt\":\"p\",\"text\":null}");
        ScriptedDialogUi broken = new ScriptedDialogUi() {
            @Override
            public Optional<String> askText(String title, String prompt) {
                throw new java.awt.HeadlessException();
            }
        };

        assertThat(agent(broken).pollOnce()).isTrue();

        assertThat(calls.get(2)).isEqualTo("POST /api/dialogs/d4/cancel");
    }

    @Test
    void aWorkerThatGaveUpMeanwhileAnswers404AndTheAnswerIsIgnoredWithoutAnError() throws Exception {
        nextResponses.add("{\"id\":\"d5\",\"kind\":\"INPUT\",\"title\":\"t\",\"prompt\":\"p\",\"text\":null}");
        statusOfReports.set(404);                         // the receipt was accepted, but by the time the person answered ...

        assertThat(agent(new ScriptedDialogUi().answer("late")).pollOnce()).isTrue();      // no exception
    }

    @Test
    void aDialogTheWorkerAlreadyGaveUpIsNotEvenShown() throws Exception {
        nextResponses.add("{\"id\":\"d7\",\"kind\":\"INPUT\",\"title\":\"t\",\"prompt\":\"p\",\"text\":null}");
        statusOfReceipt.set(404);
        ScriptedDialogUi ui = new ScriptedDialogUi().answer("nobody asked");

        assertThat(agent(ui).pollOnce()).isTrue();

        assertThat(ui.prompts()).isEmpty();               // no window for a question nobody waits for
        assertThat(calls).doesNotContain("POST /api/dialogs/d7/answer {\"text\":\"nobody asked\"}");
        assertThat(calls).hasSize(2);                     // the poll and the receipt, nothing else
    }

    @Test
    void aRefusedTokenIsExplained() {
        statusOfNext = 401;

        assertThatThrownBy(() -> agent(new ScriptedDialogUi()).pollOnce()).isInstanceOf(IOException.class)
                .hasMessageContaining("refused the token").hasMessageContaining("ISAWORKER_DIALOG_TOKEN");
    }

    @Test
    void theRunLoopKeepsPollingUntilItIsStoppedAndSurvivesAnUnreachableWorker() throws Exception {
        nextResponses.add("{\"id\":\"d6\",\"kind\":\"INPUT\",\"title\":\"t\",\"prompt\":\"p\",\"text\":null}");
        ScriptedDialogUi ui = new ScriptedDialogUi().answer("one");
        DialogAgent agent = agent(ui);
        Thread thread = new Thread(agent::run, "agent");
        thread.start();

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> calls.stream().anyMatch(c -> c.contains("/d6/answer")));
        worker.stop(0);                                  // the worker (container) goes away: the agent must not die
        Thread.sleep(1_500);
        assertThat(thread.isAlive()).isTrue();

        agent.stop();
        thread.interrupt();
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();
    }

    // --- the small pieces around the agent ---------------------------------------------------------------------------

    @Test
    void consoleDialogsReadTheAnswersFromTheInputAndEndOfInputIsACancel() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleDialogs console = new ConsoleDialogs(new ByteArrayInputStream("\n  Rajiv Verma \n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(console.askText("enter-name-topic", "Please enter your name:")).contains("Rajiv Verma");   // the empty line is skipped
        assertThat(console.askText("enter-address-topic", "Please enter your address:")).isEmpty();           // end of input
        console.showReadOnly("Worker details", "Name: Rajiv Verma");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[enter-name-topic] Please enter your name:")
                .contains("(empty: please enter a value)").contains("[Worker details]").contains("Name: Rajiv Verma");
    }

    @Test
    void theAgentOptionsAreReadAsNameValuePairs() {
        Map<String, String> options = DialogAgentMain.parse(new String[]{"--url", "http://localhost:8181", "--token", "abc",
                "--ui", "console", "stray", "--flag-without-value"});

        assertThat(options).containsEntry("url", "http://localhost:8181").containsEntry("token", "abc")
                .containsEntry("ui", "console").doesNotContainKey("flag-without-value");
    }
}
