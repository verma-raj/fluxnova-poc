package com.example.isaworker.gui;

/**
 * The person cancelled or closed a dialog (or, without a desktop, supplied no answer). Not a program error: it is reported
 * to the engine as a task failure, without a stack trace, so the task is offered again and, once its retries are used up,
 * shows up as an incident that an operator can see and resolve.
 */
public class UserCancelledException extends RuntimeException {

    public UserCancelledException(String message) {
        super(message);
    }
}
