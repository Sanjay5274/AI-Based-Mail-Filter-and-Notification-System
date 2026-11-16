package com.inboxiq.common.exception;

/**
 * Thrown when the Gmail API returns an unexpected error that is not specifically
 * handled (e.g. 5xx server errors, quota exhaustion, transient network failures).
 */
public class GmailApiException extends RuntimeException {

    private final int httpStatus;
    private final String gmailErrorCode;

    public GmailApiException(String message, int httpStatus, String gmailErrorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.gmailErrorCode = gmailErrorCode;
    }

    public GmailApiException(String message, int httpStatus, String gmailErrorCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.gmailErrorCode = gmailErrorCode;
    }

    public GmailApiException(String message) {
        super(message);
        this.httpStatus = 500;
        this.gmailErrorCode = "GMAIL_API_ERROR";
    }

    public GmailApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 500;
        this.gmailErrorCode = "GMAIL_API_ERROR";
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getGmailErrorCode() {
        return gmailErrorCode;
    }
}
