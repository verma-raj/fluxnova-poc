package com.example.fluxnova.engine;

import lombok.Getter;

/** A tenant could not be onboarded (database, pool or engine). The failure is recorded and retried with back-off. */
@Getter
public class TenantOnboardingException extends RuntimeException {

    private final String tenantId;

    public TenantOnboardingException(String tenantId, String message) {
        super("Tenant '" + tenantId + "': " + message);
        this.tenantId = tenantId;
    }

    public TenantOnboardingException(String tenantId, String message, Throwable cause) {
        super("Tenant '" + tenantId + "': " + message, cause);
        this.tenantId = tenantId;
    }
}
