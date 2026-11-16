package com.inboxiq.common.exception;

/**
 * Thrown when Google reports that the stored OAuth credentials (access or refresh token)
 * have been revoked or are permanently invalid. The caller should deactivate the
 * connected account and prompt the user to reconnect.
 */
public class GoogleCredentialsRevokedException extends RuntimeException {

    public GoogleCredentialsRevokedException(String message) {
        super(message);
    }

    public GoogleCredentialsRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
