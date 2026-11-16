package com.inboxiq.common.exception;

import com.inboxiq.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> details = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.put(error.getField(), error.getDefaultMessage());
        }

        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Invalid request parameters",
                request.getRequestURI(),
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "BAD_REQUEST",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(ConflictException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "RESOURCE_CONFLICT",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler({UnauthorizedException.class, BadCredentialsException.class})
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(Exception ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHORIZED",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(TokenReuseException.class)
    public ResponseEntity<ErrorResponse> handleTokenReuseException(TokenReuseException ex, HttpServletRequest request) {
        log.warn("Security Alert: Refresh token reuse detected from IP: {}", request.getRemoteAddr());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "TOKEN_REUSE_DETECTED",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN",
                "Access denied",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(GmailApiException.class)
    public ResponseEntity<ErrorResponse> handleGmailApiException(GmailApiException ex, HttpServletRequest request) {
        log.warn("Gmail API error at {}: [{}] {}", request.getRequestURI(), ex.getGmailErrorCode(), ex.getMessage());
        // Map Gmail-side 429 / 503 to 502/503 on our side; other errors become 502
        HttpStatus status = switch (ex.getHttpStatus()) {
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default  -> HttpStatus.BAD_GATEWAY;
        };
        ErrorResponse response = new ErrorResponse(
                status.value(),
                ex.getGmailErrorCode(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(GoogleCredentialsRevokedException.class)
    public ResponseEntity<ErrorResponse> handleGoogleCredentialsRevokedException(
            GoogleCredentialsRevokedException ex, HttpServletRequest request) {
        log.warn("Google credentials revoked at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "GOOGLE_CREDENTIALS_REVOKED",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled server exception at {}", request.getRequestURI(), ex);
        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}

