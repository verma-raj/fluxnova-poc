package com.example.isaworker.gui;

import com.example.isaworker.IsaWorkerApplication;
import com.example.isaworker.agent.DialogAgent;
import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole bridge with REAL windows: a worker in "the container" (the application in remote mode, here in this JVM) asks for
 * an answer, the dialog agent fetches the dialog over HTTP and opens a real Swing dialog, and a "user" thread types into it
 * and presses its buttons; the answer travels back to the worker.
 * <p>
 * It opens windows, so it never runs in a normal build:
 * <pre>
 *   mvn -Dtest=RemoteBridgeSwingTest -Disaworker.swing.test=true -DargLine=-Djava.awt.headless=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "isaworker.swing.test", matches = "true")
@Timeout(90)
class RemoteBridgeSwingTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String TOKEN = "swing-bridge-token";

    private ConfigurableApplicationContext worker;
    private DialogAgent agent;
    private Thread agentThread;

    @BeforeEach
    void startWorkerAndAgent() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a desktop");
        worker = new SpringApplicationBuilder(IsaWorkerApplication.class).run("--server.port=0", "--spring.main.banner-mode=off",
                "--FLUXNOVA_WORKERS_CONFIG=none-" + System.nanoTime() + ".yml", "--isaworker.gui.mode=remote",
                "--isaworker.gui.remote.token=" + TOKEN, "--isaworker.gui.remote.poll-wait=1s");
        agent = new DialogAgent("http://localhost:" + worker.getEnvironment().getProperty("local.server.port"), TOKEN,
                new SwingDialogs(), Duration.ofSeconds(1));
        agentThread = new Thread(agent::run, "dialog-agent");
        agentThread.setDaemon(true);
        agentThread.start();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (agent != null) {
            agent.stop();
            agentThread.interrupt();
            agentThread.join(10_000);
        }
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()) {
                SwingUtilities.invokeLater(dialog::dispose);       // a failed test must not leave a dialog on the screen
            }
        }
        if (worker != null) {
            worker.close();
        }
    }

    private static ExternalTask task(String topic) {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getTopicName()).thenReturn(topic);
        when(task.getProcessInstanceId()).thenReturn("55");
        return task;
    }

    private static JDialog awaitDialog(String topic) {
        AtomicReference<JDialog> found = new AtomicReference<>();
        await().atMost(WAIT).until(() -> {
            Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog d && d.isShowing()
                    && d.getTitle().contains(topic)).findFirst().ifPresent(w -> found.set((JDialog) w));
            return found.get() != null;
        });
        return found.get();
    }

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(all(container, type));
            }
        }
        return found;
    }

    private static void press(JDialog dialog, String label) throws Exception {
        AbstractButton button = all(dialog.getContentPane(), AbstractButton.class).stream()
                .filter(b -> label.equalsIgnoreCase(b.getText())).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(button::doClick);
    }

    private static void type(JDialog dialog, String text) throws Exception {
        JTextField field = all(dialog.getContentPane(), JTextField.class).get(0);
        SwingUtilities.invokeAndWait(() -> field.setText(text));
    }

    private Thread ask(AtomicReference<Optional<String>> answer, String topic, String prompt) {
        DialogService service = worker.getBean(DialogService.class);
        Thread thread = new Thread(() -> answer.set(service.askText(prompt, task(topic), "name")), "worker-thread");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    void theWindowOpensOnTheAgentAndTheTypedAnswerReachesTheWorker() throws Exception {
        assertThat(worker.getBean(DialogService.class).mode()).isEqualTo("remote");
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread workerThread = ask(answer, "enter-name-topic", "Please enter your name:");

        JDialog dialog = awaitDialog("enter-name-topic");
        assertThat(dialog.getTitle()).contains("process instance 55");
        assertThat(dialog.isAlwaysOnTop()).isTrue();
        assertThat(workerThread.isAlive()).isTrue();                // the worker waits while the person types
        type(dialog, "  Rajiv Verma  ");
        press(dialog, "OK");

        workerThread.join(WAIT.toMillis());
        assertThat(answer.get()).contains("Rajiv Verma");
    }

    @Test
    void cancellingTheRealWindowGivesTheWorkerNoAnswer() throws Exception {
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread workerThread = ask(answer, "enter-address-topic", "Please enter your address:");

        JDialog dialog = awaitDialog("enter-address-topic");
        type(dialog, "typed, then cancelled");
        press(dialog, "Cancel");

        workerThread.join(WAIT.toMillis());
        assertThat(answer.get()).isEmpty();
    }

    @Test
    void theReadOnlyDetailsWindowOpensOnTheAgentAndTheWorkerContinuesWhenItIsClosed() throws Exception {
        DialogService service = worker.getBean(DialogService.class);
        Thread workerThread = new Thread(() -> service.showReadOnly("Worker details", "Name: Rajiv Verma\nAddress: Bangalore",
                task("show-details-topic")), "worker-thread");
        workerThread.setDaemon(true);
        workerThread.start();

        JDialog dialog = awaitDialog("show-details-topic");
        JTextArea area = all(dialog.getContentPane(), JTextArea.class).get(0);
        assertThat(area.isEditable()).isFalse();
        assertThat(area.getText()).isEqualTo("Name: Rajiv Verma\nAddress: Bangalore");
        assertThat(workerThread.isAlive()).isTrue();
        press(dialog, "OK");

        workerThread.join(WAIT.toMillis());
        assertThat(workerThread.isAlive()).isFalse();
    }
}
