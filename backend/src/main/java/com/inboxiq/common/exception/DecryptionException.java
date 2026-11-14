package com.inboxiq.common.exception;

public class DecryptionException extends InboxIqException {

    public DecryptionException(String message) {
        super(message);
    }

    public DecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}

