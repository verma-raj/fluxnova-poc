package com.example.fluxnova.config;

/** Thrown when tenants.yml cannot be parsed or fails validation. The previously active configuration stays in force. */
public class TenantConfigException extends RuntimeException {

    public TenantConfigException(String message) {
        super(message);
    }

    public TenantConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
