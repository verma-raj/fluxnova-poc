package com.example.isaworker.agent;

import com.example.isaworker.gui.DialogUi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Dialogs on the console instead of in windows ({@code --ui console} of the agent): for a desktop session without a graphical
 * display (an SSH session, a build agent) and for scripted use, where the answers are piped into the agent's standard input,
 * one line per question. End of input counts as cancel.
 */
public class ConsoleDialogs implements DialogUi {

    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleDialogs(InputStream in, PrintStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    @Override
    public synchronized Optional<String> askText(String title, String prompt) {
        while (true) {
            out.println("[" + title + "] " + prompt);
            String line = readLine();
            if (line == null) {
                return Optional.empty();                      // end of input: nobody to answer
            }
            if (!line.isBlank()) {
                return Optional.of(line.trim());
            }
            out.println("(empty: please enter a value)");
        }
    }

    @Override
    public synchronized void showReadOnly(String title, String text) {
        out.println("[" + title + "]");
        out.println(text);
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
