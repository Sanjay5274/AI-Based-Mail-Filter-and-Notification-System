package com.inboxiq.common.exception;

public class InboxIqException extends RuntimeException {

    public InboxIqException(String message) {
        super(message);
    }

    public InboxIqException(String message, Throwable cause) {
        super(message, cause);
    }
}

