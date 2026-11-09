package com.example.isaworker.gui.remote;

import com.example.isaworker.gui.UserCancelledException;

/**
 * A dialog was sent to the desktop but nobody answered in time. Like a cancelled dialog it is reported to the engine as a
 * failed task (the message says whether the agent was connected); the task is offered again after the retry pause.
 */
public class NoAnswerException extends UserCancelledException {

    public NoAnswerException(String message) {
        super(message);
    }
}
