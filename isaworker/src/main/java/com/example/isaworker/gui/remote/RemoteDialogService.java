package com.example.isaworker.gui.remote;

import com.example.isaworker.config.GuiProperties;
import com.example.isaworker.gui.DialogService;
import com.example.isaworker.gui.remote.DialogBroker.Kind;
import com.example.isaworker.gui.remote.DialogBroker.Outcome;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dialogs on the desktop of the machine that runs the container ({@code isaworker.gui.mode=remote}). Nothing is shown in this
 * JVM: each dialog is queued in the {@link DialogBroker}, the dialog agent on the desktop fetches it over HTTP, shows a real
 * Swing window, and sends the answer back; the worker thread waits meanwhile.
 */
public class RemoteDialogService implements DialogService {

    private final DialogBroker broker;
    private final GuiProperties.Remote settings;

    public RemoteDialogService(DialogBroker broker, GuiProperties.Remote settings) {
        this.broker = broker;
        this.settings = settings;
    }

    @Override
    public Optional<String> askText(String prompt, ExternalTask task, String suppliedVariable) {
        Outcome outcome = broker.ask(Kind.INPUT, DialogService.titleOf(task), prompt, null, settings.getAnswerTimeout(),
                settings.getAgentTimeout());
        if (!outcome.answered() || outcome.text() == null || outcome.text().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(outcome.text().trim());
    }

    @Override
    public void showReadOnly(String title, String text, ExternalTask task) {
        broker.ask(Kind.MESSAGE, title + " - " + DialogService.titleOf(task), null, text, settings.getAnswerTimeout(),
                settings.getAgentTimeout());
    }

    @Override
    public String mode() {
        return "remote";
    }

    @Override
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", mode());
        details.put("agentConnected", broker.agentConnected(settings.getAgentTimeout()));
        details.put("lastAgentPoll", broker.lastAgentPoll());
        details.put("dialogsWaiting", broker.waitingDialogs());
        return details;
    }
}
