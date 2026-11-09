package com.example.isaworker.gui.remote;

import com.example.isaworker.gui.UserCancelledException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The meeting point between the worker threads, which need a person to answer a dialog, and the dialog agent, which shows the
 * dialogs on the desktop of the machine the container runs on.
 * <p>
 * A worker calls {@link #ask} and waits. The agent calls {@link #next} (long polling over HTTP, see
 * {@link DialogBridgeController}), gets the dialog, confirms it with {@link #received}, shows it, and reports the outcome with
 * {@link #answer}, {@link #cancel} or {@link #acknowledge}, which wakes the worker. Nothing is stored: a dialog exists only
 * while its worker waits. If the worker gives up (timeout), the dialog disappears from the queue and a late answer is refused.
 * <p>
 * <b>Delivery receipt.</b> A dialog that was handed to an agent but not confirmed within the receipt timeout goes back into
 * the queue. Without that, an agent that was killed while its long-poll request was still open at the container would
 * "take" the next dialog into a dead connection, and the dialog would be lost although a new agent is connected. Once the
 * agent has confirmed, the dialog is its own: the person may take as long as they need.
 * <p>
 * The agent connects <em>to</em> the container (which publishes its port on the host's loopback address), never the other way
 * round, so nothing listens on the desktop and no firewall rule is needed.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "isaworker.gui", name = "mode", havingValue = "remote")
public class DialogBroker {

    public enum Kind {
        /** A question with a text field: the outcome carries the text. */
        INPUT,
        /** A read-only message: the outcome is only "closed". */
        MESSAGE
    }

    /** A dialog that waits to be shown. */
    public record Request(String id, Kind kind, String title, String prompt, String text) {
    }

    /** What the person did: {@code answered} with {@code text}, or cancelled / closed it. */
    public record Outcome(boolean answered, String text) {
    }

    /** A dialog and what has happened to it so far. Its fields are guarded by {@code synchronized (this)}. */
    private static final class Pending {
        final Request request;
        final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        Instant handedAt;            // when it was given to an agent; null while it waits in the queue
        boolean received;            // the agent has confirmed that it has the dialog

        Pending(Request request) {
            this.request = request;
        }
    }

    static final Duration DEFAULT_RECEIPT_TIMEOUT = Duration.ofSeconds(5);

    private final Duration receiptTimeout;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile Instant lastAgentPoll;

    public DialogBroker() {
        this(DEFAULT_RECEIPT_TIMEOUT);
    }

    DialogBroker(Duration receiptTimeout) {
        this.receiptTimeout = receiptTimeout;
    }

    /**
     * Queues a dialog and waits for the agent's outcome. Called on a worker thread.
     *
     * @throws NoAnswerException    if nobody answered within {@code timeout} (no agent, or the person walked away)
     * @throws IllegalStateException if the application shuts down while waiting
     */
    public Outcome ask(Kind kind, String title, String prompt, String text, Duration timeout, Duration agentTimeout) {
        Pending entry = new Pending(new Request(UUID.randomUUID().toString(), kind, title, prompt, text));
        pending.put(entry.request.id(), entry);
        queue.add(entry);
        if (!agentConnected(agentTimeout)) {
            log.warn("A dialog is waiting for the desktop (\"{}\"), but no dialog agent is connected. Start run-dialog-agent.ps1 "
                    + "on the desktop; the task waits for up to {}.", title, timeout);
        }
        try {
            return entry.outcome.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new NoAnswerException("Nobody answered the dialog \"" + title + "\" within " + timeout.toMinutes() + " min"
                    + (agentConnected(agentTimeout) ? " (the dialog agent is connected: the person did not answer)"
                    : " (no dialog agent is connected: start run-dialog-agent.ps1 on the desktop)"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the dialog", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } finally {
            pending.remove(entry.request.id());
            queue.remove(entry);
        }
    }

    /**
     * The agent asks for the next dialog; waits up to {@code wait} for one. Also records that the agent is alive. The dialog
     * must then be confirmed with {@link #received}, or it is offered again.
     *
     * @return the next dialog, or empty if none arrived in time
     */
    public Optional<Request> next(Duration wait) throws InterruptedException {
        lastAgentPoll = Instant.now();
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            requeueUnconfirmed();
            long left = deadline - System.nanoTime();
            // Wait in slices, so that a dialog whose receipt never came is put back even while nothing else happens.
            Pending entry = queue.poll(Math.min(Math.max(left, 0), TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS);
            if (entry == null) {
                if (left <= 0) {
                    return Optional.empty();
                }
                continue;
            }
            if (!pending.containsKey(entry.request.id())) {
                continue;                                            // its worker has given up meanwhile
            }
            synchronized (entry) {
                entry.handedAt = Instant.now();
                entry.received = false;
            }
            lastAgentPoll = Instant.now();
            return Optional.of(entry.request);
        }
    }

    /** The agent has the dialog and is about to show it. @return false if the dialog is gone (the worker gave up) */
    public boolean received(String id) {
        Pending entry = pending.get(id);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            entry.received = true;
        }
        return true;
    }

    /** The person answered. @return false if the dialog is unknown or already gone (the worker gave up) */
    public boolean answer(String id, String text) {
        return finish(id, new Outcome(true, text));
    }

    /** The person cancelled or closed the dialog. */
    public boolean cancel(String id) {
        return finish(id, new Outcome(false, null));
    }

    /** The person closed a read-only message. */
    public boolean acknowledge(String id) {
        return finish(id, new Outcome(true, null));
    }

    private boolean finish(String id, Outcome outcome) {
        Pending entry = pending.get(id);
        return entry != null && entry.outcome.complete(outcome);
    }

    /** Puts dialogs back into the queue that were handed to an agent that never confirmed them (it died or lost the answer). */
    private void requeueUnconfirmed() {
        Instant tooOld = Instant.now().minus(receiptTimeout);
        for (Pending entry : pending.values()) {
            synchronized (entry) {
                if (entry.handedAt != null && !entry.received && entry.handedAt.isBefore(tooOld) && !entry.outcome.isDone()) {
                    entry.handedAt = null;
                    queue.add(entry);
                    log.warn("Dialog \"{}\" was handed to a dialog agent that did not confirm it within {}: offering it again",
                            entry.request.title(), receiptTimeout);
                }
            }
        }
    }

    /** True if the agent asked for work recently. */
    public boolean agentConnected(Duration within) {
        Instant last = lastAgentPoll;
        return last != null && last.isAfter(Instant.now().minus(within));
    }

    public Instant lastAgentPoll() {
        return lastAgentPoll;
    }

    public int waitingDialogs() {
        return pending.size();
    }

    /** Releases the workers that wait for a dialog, so that the application can stop. */
    @PreDestroy
    void close() {
        pending.values().forEach(entry -> entry.outcome.completeExceptionally(
                new UserCancelledException("isaworker is shutting down; the dialog was not answered")));
    }
}
