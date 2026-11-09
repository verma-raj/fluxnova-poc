package com.example.isaworker;

import com.example.isaworker.gui.DialogUi;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the person at the desktop: answers come from a script, and everything that was "shown" is recorded. Used
 * wherever a test needs the dialog agent without opening real windows.
 */
public class ScriptedDialogUi implements DialogUi {

    private final Queue<Optional<String>> answers = new ConcurrentLinkedQueue<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();

    /** The next question is answered with this text. */
    public ScriptedDialogUi answer(String text) {
        answers.add(Optional.of(text));
        return this;
    }

    /** The next question is cancelled. */
    public ScriptedDialogUi cancel() {
        answers.add(Optional.empty());
        return this;
    }

    @Override
    public Optional<String> askText(String title, String prompt) {
        titles.add(title);
        prompts.add(prompt);
        Optional<String> answer = answers.poll();
        return answer == null ? Optional.empty() : answer;     // nobody scripted: the person walks away = cancel
    }

    @Override
    public void showReadOnly(String title, String text) {
        titles.add(title);
        messages.add(text);
    }

    public List<String> titles() {
        return List.copyOf(titles);
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }
}
