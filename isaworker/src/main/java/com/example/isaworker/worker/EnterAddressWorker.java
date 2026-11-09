package com.example.isaworker.worker;

import com.example.isaworker.gui.UserCancelledException;
import com.example.isaworker.gui.DialogService;
import com.example.isaworker.persistence.WorkerDetailService;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;

/**
 * Worker 2, the task {@code enter_address} (topic {@value #TOPIC}): asks "Please enter your address:", updates the
 * {@code WorkerDetail} row of the process instance with the address and
 * completes the task. Only if {@code isaworker.workers.set-process-variables} is on does it also set the process variable
 * {@value #VARIABLE} (otherwise the address stays in {@code workerdb} and out of the engine's database).
 * <p>
 * A cancelled dialog, or a missing row (the name step did not run), becomes a failure report to the engine.
 */
@Slf4j
public class EnterAddressWorker extends AbstractTenantWorker {

    public static final String TOPIC = "enter-address-topic";
    public static final String VARIABLE = "address";
    public static final String PROMPT = "Please enter your address:";

    private final WorkerDetailService details;
    private final DialogService dialogs;
    private final long retryTimeoutMillis;
    private final boolean setProcessVariables;

    public EnterAddressWorker(String tenantId, WorkerDetailService details, DialogService dialogs, long retryTimeoutMillis,
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
        // Check the row first: there is no point in asking for an address that cannot be stored.
        details.find(tenantId, task.getProcessInstanceId());
        String address = dialogs.askText(PROMPT, task, VARIABLE)
                .orElseThrow(() -> new UserCancelledException("The address dialog was cancelled or closed without an answer"));
        details.saveAddress(tenantId, task.getProcessInstanceId(), address);
        log.info("[{}] Address \"{}\" saved to workerdb for process instance {}", tenantId, address, task.getProcessInstanceId());
        return setProcessVariables ? Map.<String, Object>of(VARIABLE, address) : Map.of();
    }

    @Override
    protected long retryTimeoutMillis() {
        return retryTimeoutMillis;
    }
}
