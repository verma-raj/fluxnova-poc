package com.example.isaworker.gui;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Real desktop dialogs: {@link JOptionPane}. Needs a JVM with a display.
 * <p>
 * Swing is not thread safe, and the callers are worker or network threads, so every dialog is created and shown on the event
 * dispatch thread ({@link SwingUtilities#invokeAndWait}). That call returns only when the dialog is closed, which is what the
 * caller wants: it carries on after the person has answered. Dialogs are "always on top", so one that opens while another
 * window has the focus is not left hidden behind it.
 */
public class SwingDialogs implements DialogUi {

    static final String EMPTY_HINT = "\n(Please enter a value, or press Cancel.)";

    @Override
    public Optional<String> askText(String title, String prompt) {
        String text = prompt;
        while (true) {
            String shown = text;
            String entered = onEventThread(() -> input(shown, title));
            if (entered == null) {
                return Optional.empty();                      // cancelled or closed with the window's X
            }
            if (!entered.isBlank()) {
                return Optional.of(entered.trim());
            }
            text = prompt + EMPTY_HINT;                       // an empty answer is no answer: ask again
        }
    }

    @Override
    public void showReadOnly(String title, String text) {
        onEventThread(() -> {
            JTextArea area = new JTextArea(text);
            area.setEditable(false);                          // read-only: the text can be selected and copied, not changed
            area.setRows(Math.min(12, Math.max(3, (int) text.lines().count())));
            area.setColumns(40);
            JOptionPane pane = new JOptionPane(new JScrollPane(area), JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION);
            show(pane, title);
            return null;
        });
    }

    /** @return what was typed, or null if the person cancelled / closed the dialog */
    private static String input(String prompt, String title) {
        JOptionPane pane = new JOptionPane(prompt, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        show(pane, title);
        Object choice = pane.getValue();
        if (!(choice instanceof Integer option) || option != JOptionPane.OK_OPTION) {
            return null;
        }
        Object value = pane.getInputValue();
        return value == JOptionPane.UNINITIALIZED_VALUE || value == null ? null : value.toString();
    }

    /** Shows the pane in a modal, always-on-top dialog and returns when the dialog is closed. */
    private static void show(JOptionPane pane, String title) {
        JDialog dialog = pane.createDialog(null, title);
        dialog.setAlwaysOnTop(true);
        dialog.setLocationRelativeTo(null);
        try {
            dialog.setVisible(true);                          // blocks (modal) until OK / Cancel / close
        } finally {
            dialog.dispose();
        }
    }

    private static <T> T onEventThread(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(action.get());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the dialog", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("The dialog failed: " + e.getCause(), e.getCause());
        }
        if (failure.get() != null) {
            throw new IllegalStateException("The dialog failed: " + failure.get(), failure.get());
        }
        return result.get();
    }
}
