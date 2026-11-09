package com.example.isaworker.config;

/** The tenant configuration file could not be understood (YAML syntax error, a value of the wrong shape, ...). */
public class EngineConfigException extends RuntimeException {

    public EngineConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
