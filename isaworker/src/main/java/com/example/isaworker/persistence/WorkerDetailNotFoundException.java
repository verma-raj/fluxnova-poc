package com.example.isaworker.persistence;

/** There is no {@link WorkerDetail} for the process instance: an earlier step of the process did not run, or the data is gone. */
public class WorkerDetailNotFoundException extends RuntimeException {

    public WorkerDetailNotFoundException(String tenantId, String processInstanceId) {
        super("workerdb has no row for process instance " + processInstanceId + " of tenant '" + tenantId
                + "' - did the 'enter_name' task run for it?");
    }
}
