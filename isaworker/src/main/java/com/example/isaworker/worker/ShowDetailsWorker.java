package com.example.isaworker.worker;

import com.example.isaworker.gui.DialogService;
import com.example.isaworker.persistence.WorkerDetail;
import com.example.isaworker.persistence.WorkerDetailService;
import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;

/**
 * Worker 3, the task {@code show_details} (topic {@value #TOPIC}): reads the {@code WorkerDetail} row of the process
 * instance from {@code workerdb} (Spring Data JPA), shows the stored name and address in a read-only dialog and completes
 * the task when the dialog is closed.
 * <p>
 * A missing row becomes a failure report to the engine. The data is read from the database on purpose, not from process
 * variables: that proves the workers really share their state through {@code workerdb}.
 */
@Slf4j
public class ShowDetailsWorker extends AbstractTenantWorker {

    public static final String TOPIC = "show-details-topic";
    public static final String TITLE = "Worker details";

    private final WorkerDetailService details;
    private final DialogService dialogs;
    private final long retryTimeoutMillis;

    public ShowDetailsWorker(String tenantId, WorkerDetailService details, DialogService dialogs, long retryTimeoutMillis) {
        super(tenantId);
        this.details = details;
        this.dialogs = dialogs;
        this.retryTimeoutMillis = retryTimeoutMillis;
    }

    @Override
    protected Map<String, Object> handle(ExternalTask task) {
        logStart(task);
        WorkerDetail detail = details.find(tenantId, task.getProcessInstanceId());
        String text = "Name: " + detail.getName() + "\nAddress: " + detail.getAddress();
        log.info("[{}] Details of process instance {} read from workerdb: name=\"{}\", address=\"{}\"", tenantId,
                task.getProcessInstanceId(), detail.getName(), detail.getAddress());
        dialogs.showReadOnly(TITLE, text, task);
        return Map.of();                                   // nothing to return: completing the task ends the process
    }

    @Override
    protected long retryTimeoutMillis() {
        return retryTimeoutMillis;
    }
}
