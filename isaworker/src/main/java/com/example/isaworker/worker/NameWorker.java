package com.example.isaworker.worker;

import lombok.extern.slf4j.Slf4j;
import org.finos.fluxnova.bpm.client.task.ExternalTask;

import java.util.Map;

/**
 * Handler of the topic {@value #TOPIC} ("Service Task 1: Return Name"): logs which tenant it runs for and returns the
 * process variable {@value #OUTPUT_VARIABLE}.
 */
@Slf4j
public class NameWorker extends AbstractTenantWorker {

    public static final String TOPIC = "return-name";
    public static final String OUTPUT_VARIABLE = "customerName";
    public static final String CUSTOMER_NAME = "Rajiv Verma";

    public NameWorker(String tenantId) {
        super(tenantId);
    }

    @Override
    protected Map<String, Object> handle(ExternalTask task) {
        logStart(task);
        log.info("[{}] Executing '{}' for tenant {}: returning {} = \"{}\"", tenantId, TOPIC, tenantId, OUTPUT_VARIABLE, CUSTOMER_NAME);
        return Map.<String, Object>of(OUTPUT_VARIABLE, CUSTOMER_NAME);
    }
}
