package com.example.isaworker.agent;

import com.example.isaworker.gui.DialogUi;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The program that runs ON THE DESKTOP and shows the dialogs of a worker that runs somewhere else, typically in a Docker
 * container ({@code isaworker.gui.mode=remote}): a window can only appear on the screen of the machine that has the screen.
 * <p>
 * It connects <em>to</em> the worker (the container publishes its port on the host's loopback address), so nothing listens on
 * the desktop. Loop: ask {@code GET /api/dialogs/next} (the worker holds the request open for up to the poll wait while there
 * is nothing to show); when a dialog arrives, show it with the {@link DialogUi} (real Swing windows) and report what the person
 * did: {@code /answer}, {@code /cancel} or {@code /ack}. If the worker is not reachable, it retries with growing pauses and
 * logs once when the state changes, so it can be started before or after the container.
 */
@Slf4j
public final class DialogAgent {

    /** A dialog as the worker sends it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dialog(String id, String kind, String title, String prompt, String text) {
    }

    private final URI base;
    private final String token;
    private final DialogUi ui;
    private final Duration pollWait;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);

    private volatile boolean running = true;
    private String lastProblem;

    public DialogAgent(String baseUrl, String token, DialogUi ui, Duration pollWait) {
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        this.token = token;
        this.ui = ui;
        this.pollWait = pollWait;
    }

    /** Runs until {@link #stop()} (or the thread is interrupted). */
    public void run() {
        log.info("Dialog agent {} started: showing the dialogs of {} on this desktop", agentId, base);
        long pauseMillis = 1_000;
        while (running) {
            try {
                pollOnce();
                if (lastProblem != null) {
                    log.info("Connected to {} again", base);
                    lastProblem = null;
                }
                pauseMillis = 1_000;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                String problem = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!problem.equals(lastProblem)) {                 // say it once per kind of problem, not every few seconds
                    lastProblem = problem;
                    log.warn("Cannot get dialogs from {}: {}. Retrying.", base, problem);
                }
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                pauseMillis = Math.min(pauseMillis * 2, 10_000);
            }
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * One long poll. If a dialog arrives it is shown (this blocks until the person has answered) and the outcome is sent back.
     *
     * @return true if a dialog was shown
     */
    public boolean pollOnce() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/dialogs/next?wait=" + pollWait.toSeconds()
                        + "&agent=" + agentId))
                .header("Authorization", "Bearer " + token)
                .timeout(pollWait.plusSeconds(15))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        switch (response.statusCode()) {
            case 204:
                return false;
            case 200:
                show(json.readValue(response.body(), Dialog.class));
                return true;
            case 401:
                throw new IOException("the worker refused the token (HTTP 401): ISAWORKER_DIALOG_TOKEN must be the same on the "
                        + "worker and on the agent");
            default:
                throw new IOException("the worker answered HTTP " + response.statusCode());
        }
    }

    private void show(Dialog dialog) throws IOException, InterruptedException {
        log.info("Dialog {}: \"{}\"", dialog.id(), dialog.title());
        // Confirm at once, before the (possibly long) interaction: the worker offers a dialog again to another agent if
        // nobody confirmed it, so that an agent that died with a request open at the worker cannot make a dialog vanish.
        if (!confirmReceipt(dialog)) {
            log.warn("The worker no longer waits for dialog {} (it gave up): not shown", dialog.id());
            return;
        }
        try {
            if ("MESSAGE".equals(dialog.kind())) {
                ui.showReadOnly(dialog.title(), dialog.text());
                report(dialog, "ack", null);
            } else {
                Optional<String> answer = ui.askText(dialog.title(), dialog.prompt());
                if (answer.isPresent()) {
                    report(dialog, "answer", json.writeValueAsString(Map.of("text", answer.get())));
                } else {
                    report(dialog, "cancel", null);
                }
            }
        } catch (RuntimeException e) {
            // The window could not be shown (no display ...): tell the worker, so that it does not wait for nothing.
            log.error("Dialog {} could not be shown: {}", dialog.id(), e.toString());
            report(dialog, "cancel", null);
        }
    }

    /** @return false if the worker no longer knows the dialog (it gave up) */
    private boolean confirmReceipt(Dialog dialog) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/dialogs/" + dialog.id() + "/received"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == 404) {
            return false;
        }
        if (status / 100 != 2) {
            throw new IOException("the worker answered HTTP " + status + " to the receipt");
        }
        return true;
    }

    private void report(Dialog dialog, String action, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/dialogs/" + dialog.id() + "/" + action))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
        HttpResponse<String> response = http.send(request.POST(body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            log.warn("The worker no longer waits for dialog {} (it gave up): the {} is ignored", dialog.id(), action);
        } else if (response.statusCode() / 100 != 2) {
            throw new IOException("the worker answered HTTP " + response.statusCode() + " to " + action);
        } else {
            log.info("Dialog {}: {} sent to the worker", dialog.id(), action);
        }
    }
}
