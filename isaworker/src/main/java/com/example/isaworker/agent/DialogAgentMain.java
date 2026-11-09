package com.example.isaworker.agent;

import com.example.isaworker.gui.DialogUi;
import com.example.isaworker.gui.SwingDialogs;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts the dialog agent on the desktop. It is NOT a Spring application: no database, no web server, only the HTTP client and
 * the Swing windows. It lives in the isaworker jar, started with the Spring Boot properties launcher (see
 * {@code run-dialog-agent.ps1}):
 * <pre>
 *   java -Dloader.main=com.example.isaworker.agent.DialogAgentMain ^
 *        -cp isaworker-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher ^
 *        --url http://localhost:8181 --token &lt;ISAWORKER_DIALOG_TOKEN&gt;
 * </pre>
 * Options (or the environment variables in brackets): {@code --url} (ISAWORKER_URL, default http://localhost:8181),
 * {@code --token} (ISAWORKER_DIALOG_TOKEN, required), {@code --ui swing|console} (default swing),
 * {@code --poll-wait} seconds of long polling (default 20).
 */
public final class DialogAgentMain {

    private DialogAgentMain() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        String url = first(options.get("url"), System.getenv("ISAWORKER_URL"), "http://localhost:8181");
        String token = first(options.get("token"), System.getenv("ISAWORKER_DIALOG_TOKEN"), null);
        String ui = first(options.get("ui"), null, "swing");
        long pollWait = Long.parseLong(first(options.get("poll-wait"), null, "20"));
        if (token == null) {
            System.err.println("The shared secret is missing: pass --token <secret> or set ISAWORKER_DIALOG_TOKEN "
                    + "(the same value as in the worker's container).");
            System.exit(2);
            return;
        }

        DialogUi dialogs;
        if (ui.equals("console")) {
            dialogs = new ConsoleDialogs(System.in, System.out);
        } else if (ui.equals("swing")) {
            System.setProperty("java.awt.headless", "false");     // this program exists to open windows on a desktop
            dialogs = new SwingDialogs();
        } else {
            System.err.println("--ui must be swing or console");
            System.exit(2);
            return;
        }

        DialogAgent agent = new DialogAgent(url, token, dialogs, Duration.ofSeconds(pollWait));
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
        agent.run();
    }

    /** {@code --name value} pairs. */
    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    private static String first(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return fallback;
    }
}
