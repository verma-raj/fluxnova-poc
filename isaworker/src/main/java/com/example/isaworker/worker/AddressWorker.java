package com.example.isaworker.worker;

import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;

/**
 * Handler of the topic {@value #TOPIC} ("Service Task 2: Return Address"): reads the variable {@value #INPUT_VARIABLE}
 * that {@link NameWorker} set, logs it, and returns the process variable {@value #OUTPUT_VARIABLE}.
 */
@Slf4j
public class AddressWorker extends AbstractTenantWorker {

    public static final String TOPIC = "return-address";
    public static final String INPUT_VARIABLE = "customerName";
    public static final String OUTPUT_VARIABLE = "customerAddress";
    public static final String CUSTOMER_ADDRESS = "Bangalore";

    public AddressWorker(String tenantId) {
        super(tenantId);
    }

    @Override
    protected Map<String, Object> handle(ExternalTask task) {
        logStart(task);
        Object customerName = task.getVariable(INPUT_VARIABLE);
        if (customerName == null) {
            // Not a reason to fail the task: the address does not depend on the name. But it points at a model problem.
            log.warn("[{}] Executing '{}' for tenant {}: variable '{}' is not set on process instance {} - was 'return-name' skipped?",
                    tenantId, TOPIC, tenantId, INPUT_VARIABLE, task.getProcessInstanceId());
        } else {
            log.info("[{}] Executing '{}' for tenant {}: customer is \"{}\"", tenantId, TOPIC, tenantId, customerName);
        }
        log.info("[{}] Returning {} = \"{}\"", tenantId, OUTPUT_VARIABLE, CUSTOMER_ADDRESS);
        return Map.<String, Object>of(OUTPUT_VARIABLE, CUSTOMER_ADDRESS);
    }
}
