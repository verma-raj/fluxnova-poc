package com.example.isaworker.gui.remote;

import com.example.isaworker.gui.UserCancelledException;
import com.example.isaworker.gui.remote.DialogBroker.Kind;
import com.example.isaworker.gui.remote.DialogBroker.Outcome;
import com.example.isaworker.gui.remote.DialogBroker.Request;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The queue between the worker threads that wait for a person and the agent that shows the dialogs. */
class DialogBrokerTest {

    private static final Duration LONG = Duration.ofSeconds(10);
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(60);

    private final DialogBroker broker = new DialogBroker();

    private CompletableFuture<Outcome> askInBackground(Kind kind, String title, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> broker.ask(kind, title, "Please enter your name:", "details", timeout, AGENT_TIMEOUT));
    }

    private Request nextDialog() throws InterruptedException {
        Optional<Request> request = broker.next(LONG);
        assertThat(request).isPresent();
        return request.get();
    }

    @Test
    void theAnswerOfTheAgentWakesTheWaitingWorker() throws Exception {
        CompletableFuture<Outcome> worker = askInBackground(Kind.INPUT, "enter-name-topic (process instance 7)", LONG);

        Request dialog = nextDialog();
        assertThat(dialog.kind()).isEqualTo(Kind.INPUT);
        assertThat(dialog.title()).isEqualTo("enter-name-topic (process instance 7)");
        assertThat(dialog.prompt()).isEqualTo("Please enter your name:");
        assertThat(worker).isNotDone();                              // the worker waits while the person types
        assertThat(broker.answer(dialog.id(), "Rajiv Verma")).isTrue();

        assertThat(worker.get(LONG.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new Outcome(true, "Rajiv Verma"));
        assertThat(broker.waitingDialogs()).isZero();
    }

    @Test
    void cancelAndAcknowledgeAreReportedToTheWorker() throws Exception {
        CompletableFuture<Outcome> question = askInBackground(Kind.INPUT, "q", LONG);
        assertThat(broker.cancel(nextDialog().id())).isTrue();
        assertThat(question.get(LONG.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new Outcome(false, null));

        CompletableFuture<Outcome> message = askInBackground(Kind.MESSAGE, "m", LONG);
        Request shown = nextDialog();
        assertThat(shown.kind()).isEqualTo(Kind.MESSAGE);
        assertThat(shown.text()).isEqualTo("details");
        assertThat(broker.acknowledge(shown.id())).isTrue();
        assertThat(message.get(LONG.toSeconds(), TimeUnit.SECONDS).answered()).isTrue();
    }

    @Test
    void withoutAnAgentTheWorkerGivesUpAfterTheTimeoutAndSaysWhy() {
        assertThatThrownBy(() -> broker.ask(Kind.INPUT, "enter-name-topic", "Name?", null, Duration.ofMillis(300), AGENT_TIMEOUT))
                .isInstanceOf(NoAnswerException.class)
                .isInstanceOf(UserCancelledException.class)             // reported to the engine like a cancelled dialog
                .hasMessageContaining("no dialog agent is connected").hasMessageContaining("run-dialog-agent.ps1");
        assertThat(broker.waitingDialogs()).isZero();                  // and the dialog is gone from the queue
    }

    @Test
    void ifTheAgentIsConnectedButNobodyAnswersTheMessageSaysSo() throws Exception {
        assertThat(broker.next(Duration.ofMillis(10))).isEmpty();      // the agent asked: it is alive

        assertThatThrownBy(() -> broker.ask(Kind.INPUT, "enter-name-topic", "Name?", null, Duration.ofMillis(300), AGENT_TIMEOUT))
                .isInstanceOf(NoAnswerException.class).hasMessageContaining("the dialog agent is connected: the person did not answer");
        assertThat(broker.agentConnected(AGENT_TIMEOUT)).isTrue();
    }

    @Test
    void anAnswerThatComesTooLateIsRefusedAndAnAbandonedDialogIsNeverShown() throws Exception {
        CompletableFuture<Outcome> worker = askInBackground(Kind.INPUT, "gives-up", Duration.ofMillis(300));
        assertThatThrownBy(() -> worker.get(LONG.toSeconds(), TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(NoAnswerException.class);

        // The agent comes after the worker gave up: there is nothing left to show.
        assertThat(broker.next(Duration.ofMillis(200))).isEmpty();
        assertThat(broker.answer("does-not-exist", "late")).isFalse();
        assertThat(broker.cancel("does-not-exist")).isFalse();
    }

    @Test
    void theAgentGetsTheDialogsInTheOrderTheyWereQueued() throws Exception {
        askInBackground(Kind.INPUT, "first", LONG);
        await().atMost(LONG).until(() -> broker.waitingDialogs() == 1);
        askInBackground(Kind.INPUT, "second", LONG);
        await().atMost(LONG).until(() -> broker.waitingDialogs() == 2);

        Request first = nextDialog();
        Request second = nextDialog();

        assertThat(first.title()).isEqualTo("first");
        assertThat(second.title()).isEqualTo("second");
        broker.cancel(first.id());
        broker.cancel(second.id());
    }

    @Test
    void aDialogHandedToAnAgentThatNeverConfirmsIsOfferedAgain() throws Exception {
        DialogBroker broker = new DialogBroker(Duration.ofMillis(300));
        CompletableFuture<Outcome> worker = CompletableFuture.supplyAsync(() ->
                broker.ask(Kind.INPUT, "enter-name-topic", "Name?", null, LONG, AGENT_TIMEOUT));

        // An agent that was killed with its request open: it "takes" the dialog and never confirms or answers.
        Request lost = broker.next(LONG).orElseThrow();

        // A new agent asks and gets the SAME dialog again, once the receipt timeout has passed.
        Request again = broker.next(Duration.ofSeconds(5)).orElseThrow();
        assertThat(again.id()).isEqualTo(lost.id());
        assertThat(broker.received(again.id())).isTrue();
        assertThat(broker.answer(again.id(), "Rajiv Verma")).isTrue();
        assertThat(worker.get(LONG.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new Outcome(true, "Rajiv Verma"));
    }

    @Test
    void aConfirmedDialogBelongsToItsAgentAndIsNotOfferedAgainhoweverLongThePersonTakes() throws Exception {
        DialogBroker broker = new DialogBroker(Duration.ofMillis(200));
        CompletableFuture<Outcome> worker = CompletableFuture.supplyAsync(() ->
                broker.ask(Kind.INPUT, "enter-name-topic", "Name?", null, LONG, AGENT_TIMEOUT));
        Request dialog = broker.next(LONG).orElseThrow();
        assertThat(broker.received(dialog.id())).isTrue();

        Thread.sleep(700);                                             // the person is still typing: well beyond the receipt timeout

        assertThat(broker.next(Duration.ofMillis(300))).isEmpty();     // nothing is offered a second time
        assertThat(worker).isNotDone();
        broker.cancel(dialog.id());
    }

    @Test
    void aReceiptForAnUnknownDialogIsRefused() {
        assertThat(broker.received("does-not-exist")).isFalse();
    }

    @Test
    void nextReturnsEmptyWhenNothingComesWithinTheWait() throws Exception {
        long start = System.nanoTime();

        assertThat(broker.next(Duration.ofMillis(250))).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void shuttingDownReleasesTheWorkersThatWait() {
        CompletableFuture<Outcome> worker = askInBackground(Kind.INPUT, "waits", LONG);
        await().atMost(LONG).until(() -> broker.waitingDialogs() == 1);

        broker.close();

        assertThatThrownBy(() -> worker.get(LONG.toSeconds(), TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UserCancelledException.class).hasMessageContaining("shutting down");
        assertThat(worker).isCompletedExceptionally();
    }

    @Test
    void anAgentNeverSeenIsNotConnected() {
        assertThat(broker.agentConnected(AGENT_TIMEOUT)).isFalse();
        assertThat(broker.lastAgentPoll()).isNull();
    }
}
