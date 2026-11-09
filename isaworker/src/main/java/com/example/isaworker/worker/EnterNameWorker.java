package com.example.isaworker.worker;

import com.example.isaworker.gui.UserCancelledException;
import com.example.isaworker.gui.DialogService;
import com.example.isaworker.persistence.WorkerDetailService;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;

/**
 * Worker 1, the task {@code enter_name} (topic {@value #TOPIC}): asks "Please enter your name:", stores a new
 * {@code WorkerDetail} row with the process instance id and the name in {@code workerdb} (the business data stays there, not in
 * the Fluxnova engine's database) and completes the task on the Fluxnova engine. Only if {@code isaworker.workers.set-process-variables}
 * is on does it also set the process variable {@value #VARIABLE}.
 * <p>
 * If the dialog is cancelled or closed nothing is written and {@link UserCancelledException} sends a failure report to the
 * engine (see {@link AbstractTenantWorker}).
 */
@Slf4j
public class EnterNameWorker extends AbstractTenantWorker {

    public static final String TOPIC = "enter-name-topic";
    public static final String VARIABLE = "name";
    public static final String PROMPT = "Please enter your name:";

    private final WorkerDetailService details;
    private final DialogService dialogs;
    private final long retryTimeoutMillis;
    private final boolean setProcessVariables;

    public EnterNameWorker(String tenantId, WorkerDetailService details, DialogService dialogs, long retryTimeoutMillis,
                           boolean setProcessVariables) {
        super(tenantId);
        this.details = details;
        this.dialogs = dialogs;
        this.retryTimeoutMillis = retryTimeoutMillis;
        this.setProcessVariables = setProcessVariables;
    }

    @Override
    protected Map<String, Object> handle(ExternalTask task) {
        logStart(task);
        String name = dialogs.askText(PROMPT, task, VARIABLE)
                .orElseThrow(() -> new UserCancelledException("The name dialog was cancelled or closed without an answer"));
        details.saveName(tenantId, task.getProcessInstanceId(), name);
        log.info("[{}] Name \"{}\" saved to workerdb for process instance {}", tenantId, name, task.getProcessInstanceId());
        return setProcessVariables ? Map.<String, Object>of(VARIABLE, name) : Map.of();
    }

    @Override
    protected long retryTimeoutMillis() {
        return retryTimeoutMillis;
    }
}
