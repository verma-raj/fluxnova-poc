package com.example.isaworker.gui;

import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Optional;

/**
 * For a JVM without a desktop, typically isaworker in a container: no window can be shown, so
 * <ul>
 *   <li>the answers are taken from the process variables {@code name} / {@code address}, which the caller supplies when it
 *       starts the process ({@code POST .../process-definition/key/workerDetailsProcess/start} with a {@code variables}
 *       body); a task whose variable is missing is "cancelled" and reported to the engine as a failure;</li>
 *   <li>"show details" is written to the log.</li>
 * </ul>
 */
@Slf4j
public class HeadlessDialogService implements DialogService {

    @Override
    public Optional<String> askText(String prompt, ExternalTask task, String suppliedVariable) {
        Object supplied = task.getVariable(suppliedVariable);
        String text = supplied == null ? "" : supplied.toString().trim();
        if (text.isEmpty()) {
            log.warn("No display, and process instance {} has no variable '{}': cannot answer \"{}\"",
                    task.getProcessInstanceId(), suppliedVariable, prompt);
            return Optional.empty();
        }
        log.info("No display: answering \"{}\" with the variable '{}' of process instance {}", prompt, suppliedVariable,
                task.getProcessInstanceId());
        return Optional.of(text);
    }

    @Override
    public void showReadOnly(String title, String text, ExternalTask task) {
        log.info("No display: {} (process instance {}): {}", title, task.getProcessInstanceId(), text.replace("\n", " | "));
    }

    @Override
    public String mode() {
        return "headless";
    }
}
