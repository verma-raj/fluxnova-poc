package com.example.isaworker.gui;

import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;
import java.util.Optional;

/**
 * How the interactive workers talk to the person at the keyboard. The workers only know this interface; whether the answer
 * comes from a window in this JVM, from a window on the host's desktop or from a process variable is decided once, at
 * start-up ({@link DialogConfiguration}).
 */
public interface DialogService {

    /**
     * Asks for a line of text.
     *
     * @param prompt           the question, e.g. "Please enter your name:"
     * @param task             the task being worked on (for the dialog title and, without a desktop, for its variables)
     * @param suppliedVariable the process variable that holds the answer when no window can be shown
     * @return the (trimmed, non-blank) answer; empty if the person cancelled or closed the dialog, or, without windows, if
     *         the process was started without that variable
     * @throws UserCancelledException if nobody answered in time (remote mode)
     * @throws IllegalStateException  if a window is required but cannot be shown
     */
    Optional<String> askText(String prompt, ExternalTask task, String suppliedVariable);

    /**
     * Shows text the person can read but not edit, and returns when the dialog is closed.
     *
     * @throws UserCancelledException if nobody closed it in time (remote mode)
     * @throws IllegalStateException  if a window is required but cannot be shown
     */
    void showReadOnly(String title, String text, ExternalTask task);

    /** {@code "swing"}, {@code "remote"} or {@code "headless"}, for the log and the health endpoint. */
    String mode();

    /** What the health endpoint shows about the dialogs. */
    default Map<String, Object> details() {
        return Map.of("mode", mode());
    }

    /** The window title: which topic and process instance the dialog belongs to. */
    static String titleOf(ExternalTask task) {
        return "Fluxnova - " + task.getTopicName() + " (process instance " + task.getProcessInstanceId() + ")";
    }
}
