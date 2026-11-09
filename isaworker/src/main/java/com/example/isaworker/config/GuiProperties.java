package com.example.isaworker.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** The dialogs of the interactive workers ({@code isaworker.gui.*}). */
@Data
@ConfigurationProperties(prefix = "isaworker.gui")
public class GuiProperties {

    /** How the workers talk to the person at the keyboard. */
    public enum Mode {
        /** Swing if the JVM has a desktop, otherwise {@link #HEADLESS}. */
        AUTO,
        /** Always Swing ({@code JOptionPane}) in THIS JVM. Without a desktop every interactive task fails with a clear message. */
        SWING,
        /** No windows: answers come from the process variables {@code name} / {@code address}; "show" writes to the log. */
        HEADLESS,
        /**
         * The dialogs open on ANOTHER machine's desktop, typically the Windows host of the container the worker runs in: the
         * worker queues each dialog and a small desktop program, the dialog agent, fetches it over HTTP, shows it and sends
         * the answer back (see {@code isaworker.gui.remote}).
         */
        REMOTE
    }

    private Mode mode = Mode.AUTO;

    /** How long a fetched interactive task stays locked: longer than a person needs to type. */
    private Duration lockDuration = Duration.ofMinutes(15);

    /** Pause before the engine offers a task again after its dialog was cancelled or could not be shown. */
    private Duration retryTimeout = Duration.ofSeconds(30);

    /** The settings of {@link Mode#REMOTE}. */
    private final Remote remote = new Remote();

    @Data
    public static class Remote {

        /**
         * Shared secret between the worker and the dialog agent (HTTP header {@code Authorization: Bearer <token>}). Required in
         * remote mode: anybody who could call the worker's dialog API could otherwise answer the dialogs.
         */
        @ToString.Exclude
        private String token;

        /**
         * How long a worker waits for the person to answer a dialog. Keep it below {@code lock-duration}: when it passes the
         * task is reported to the engine as failed and offered again, instead of waiting for ever.
         */
        private Duration answerTimeout = Duration.ofMinutes(10);

        /** How long the worker's dialog API holds an agent's request open while there is no dialog to show (long polling). */
        private Duration pollWait = Duration.ofSeconds(20);

        /** The agent counts as connected if it asked for work within this time; shown in the health endpoint. */
        private Duration agentTimeout = Duration.ofSeconds(60);
    }
}
