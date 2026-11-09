package com.example.isaworker.gui;

import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.awt.GraphicsEnvironment;
import java.util.Optional;

/**
 * Real Swing dialogs in THIS JVM ({@code isaworker.gui.mode=swing}): isaworker started on a desktop. The windows are
 * {@link SwingDialogs}; this class adds the title (topic and process instance of the task) and refuses with a clear message
 * when the JVM has no display.
 * <p>
 * A worker that runs in a container has no display; it uses {@link com.example.isaworker.gui.remote.RemoteDialogService}
 * instead, and the windows open on the host's desktop.
 */
public class SwingDialogService implements DialogService {

    private final DialogUi windows;

    public SwingDialogService() {
        this(new SwingDialogs());
    }

    public SwingDialogService(DialogUi windows) {
        this.windows = windows;
    }

    @Override
    public Optional<String> askText(String prompt, ExternalTask task, String suppliedVariable) {
        requireDisplay();
        return windows.askText(DialogService.titleOf(task), prompt);
    }

    @Override
    public void showReadOnly(String title, String text, ExternalTask task) {
        requireDisplay();
        windows.showReadOnly(title + " - " + DialogService.titleOf(task), text);
    }

    @Override
    public String mode() {
        return "swing";
    }

    /** Whether this JVM has no display. Overridable so that the refusal can be tested on any machine. */
    protected boolean headless() {
        return GraphicsEnvironment.isHeadless();
    }

    private void requireDisplay() {
        if (headless()) {
            throw new IllegalStateException("No display is available (java.awt.headless=true), so the dialog cannot be shown. "
                    + "Use isaworker.gui.mode=remote with the dialog agent on the desktop, or isaworker.gui.mode=headless and "
                    + "start the process with the variables 'name' and 'address'.");
        }
    }
}
