package com.example.isaworker.gui;

import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
 * Drives the REAL Swing dialogs: a "user" thread finds the dialog on the screen, types into it and presses its buttons.
 * <p>
 * It opens windows, so it never runs in a normal build. Run it on a machine with a desktop with
 * <pre>
 *   mvn -Dtest=SwingDialogServiceTest -Disaworker.swing.test=true -DargLine=-Djava.awt.headless=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "isaworker.swing.test", matches = "true")
@Timeout(60)                                       // a dialog that is never answered must fail the test, not hang the build
class SwingDialogServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final SwingDialogService service = new SwingDialogService();

    private static ExternalTask task() {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getTopicName()).thenReturn("enter-name-topic");
        when(task.getProcessInstanceId()).thenReturn("99");
        return task;
    }

    @AfterEach
    void closeWhateverIsLeftOpen() {
        // A failed test must not leave a dialog on the screen (and a modal dialog would keep the worker thread waiting).
        // Only visible dialogs are closed, on the event thread and without waiting: disposing Swing's invisible shared owner
        // frame from this thread blocks for ever.
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()) {
                SwingUtilities.invokeLater(dialog::dispose);
            }
        }
    }

    private Thread ask(AtomicReference<Optional<String>> answer, String prompt) {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a desktop");
        Thread thread = new Thread(() -> answer.set(service.askText(prompt, task(), "name")), "worker-thread");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static JDialog awaitDialog() {
        AtomicReference<JDialog> found = new AtomicReference<>();
        await().atMost(WAIT).until(() -> {
            Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog d && d.isShowing()
                    && d.getTitle().contains("enter-name-topic")).findFirst().ifPresent(w -> found.set((JDialog) w));
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

    private static void onEventThread(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void press(JDialog dialog, String label) throws Exception {
        AbstractButton button = all(dialog.getContentPane(), AbstractButton.class).stream()
                .filter(b -> label.equalsIgnoreCase(b.getText())).findFirst().orElseThrow();
        onEventThread(button::doClick);
    }

    private static void type(JDialog dialog, String text) throws Exception {
        JTextField field = all(dialog.getContentPane(), JTextField.class).get(0);
        onEventThread(() -> field.setText(text));
    }

    @Test
    void theTextTheUserTypesIsReturned() throws Exception {
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread worker = ask(answer, "Please enter your name:");

        JDialog dialog = awaitDialog();
        assertThat(dialog.isAlwaysOnTop()).isTrue();
        assertThat(dialog.isModal()).isTrue();
        assertThat(dialog.getTitle()).contains("process instance 99");
        type(dialog, "  Rajiv Verma  ");
        press(dialog, "OK");

        worker.join(WAIT.toMillis());
        assertThat(answer.get()).contains("Rajiv Verma");
    }

    @Test
    void cancelGivesNoAnswer() throws Exception {
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread worker = ask(answer, "Please enter your name:");

        JDialog dialog = awaitDialog();
        type(dialog, "typed but then cancelled");
        press(dialog, "Cancel");

        worker.join(WAIT.toMillis());
        assertThat(answer.get()).isEmpty();
    }

    @Test
    void closingTheWindowWithItsCrossGivesNoAnswer() throws Exception {
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread worker = ask(answer, "Please enter your name:");

        JDialog dialog = awaitDialog();
        onEventThread(dialog::dispose);

        worker.join(WAIT.toMillis());
        assertThat(answer.get()).isEmpty();
    }

    @Test
    void anEmptyAnswerIsAskedAgainWithAHint() throws Exception {
        AtomicReference<Optional<String>> answer = new AtomicReference<>();
        Thread worker = ask(answer, "Please enter your address:");

        JDialog first = awaitDialog();
        type(first, "   ");
        press(first, "OK");
        await().atMost(WAIT).until(() -> !first.isShowing());

        JDialog second = awaitDialog();
        assertThat(all(second.getContentPane(), javax.swing.JLabel.class)).anySatisfy(label ->
                assertThat(label.getText()).contains("Please enter a value"));
        type(second, "Bangalore");
        press(second, "OK");

        worker.join(WAIT.toMillis());
        assertThat(answer.get()).contains("Bangalore");
    }

    @Test
    void theDetailsDialogIsReadOnlyAndReturnsWhenItIsClosed() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a desktop");
        Thread worker = new Thread(() -> service.showReadOnly("Worker details", "Name: Rajiv Verma\nAddress: Bangalore", task()), "worker-thread");
        worker.setDaemon(true);
        worker.start();

        JDialog dialog = awaitDialog();
        JTextArea area = all(dialog.getContentPane(), JTextArea.class).get(0);
        assertThat(area.isEditable()).isFalse();
        assertThat(area.getText()).isEqualTo("Name: Rajiv Verma\nAddress: Bangalore");
        assertThat(worker.isAlive()).isTrue();                       // still waiting for the user to close it
        press(dialog, "OK");

        worker.join(WAIT.toMillis());
        assertThat(worker.isAlive()).isFalse();
    }
}
