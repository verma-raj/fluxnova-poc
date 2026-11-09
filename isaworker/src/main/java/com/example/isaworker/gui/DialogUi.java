package com.example.isaworker.gui;

import java.util.Optional;

/**
 * What can be shown to a person: a question with a text field, and a read-only message. Implemented by real Swing windows
 * ({@link SwingDialogs}) and by the console. It knows nothing about tasks or the engine, so it can run in any program that
 * has a screen: in the worker itself ({@link SwingDialogService}) or in the dialog agent on the desktop.
 */
public interface DialogUi {

    /**
     * Asks for a line of text and returns when the person has answered.
     *
     * @return the trimmed, non-blank answer; empty if the person cancelled or closed the dialog
     */
    Optional<String> askText(String title, String prompt);

    /** Shows text the person can read but not edit, and returns when the dialog is closed. */
    void showReadOnly(String title, String text);
}
