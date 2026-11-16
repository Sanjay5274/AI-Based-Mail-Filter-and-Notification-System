package com.inboxiq.account.gmail.controller;

import com.inboxiq.account.gmail.dto.GmailMessageListResponse;
import com.inboxiq.account.gmail.dto.GmailProfileResponse;
import com.inboxiq.account.gmail.service.GmailService;
import com.inboxiq.common.dto.ApiResponse;
import com.inboxiq.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing read-only Gmail API endpoints for a connected account.
 *
 * <p><strong>Security:</strong>
 * <ul>
 *   <li>All endpoints require authentication (enforced by Spring Security config).</li>
 *   <li>User identity is derived exclusively from the JWT principal via {@link SecurityUtils};
 *       client-supplied user IDs are never trusted.</li>
 *   <li>Account ownership is verified inside {@link GmailService} — a 404 is returned
 *       for cross-user access attempts to avoid leaking account existence.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/gmail")
public class GmailController {

    private static final int DEFAULT_PAGE_SIZE = GmailService.DEFAULT_PAGE_SIZE;
    private static final int MAX_PAGE_SIZE     = GmailService.MAX_PAGE_SIZE;

    private final GmailService gmailService;

    public GmailController(GmailService gmailService) {
        this.gmailService = gmailService;
    }

    /**
     * GET /api/v1/accounts/{accountId}/gmail/profile
     *
     * <p>Returns the Gmail mailbox profile for the specified connected account.
     * The access token is refreshed automatically if expired.
     *
     * <p>Sample response:
     * <pre>
     * {
     *   "status": 200,
     *   "message": "Gmail profile retrieved successfully",
     *   "data": {
     *     "accountId": "...",
     *     "gmailAddress": "user@gmail.com",
     *     "messagesTotal": 15430,
     *     "threadsTotal": 8210,
     *     "historyId": "9876543"
     *   }
     * }
     * </pre>
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<GmailProfileResponse>> getProfile(
            @PathVariable UUID accountId
    ) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        GmailProfileResponse profile = gmailService.getProfile(accountId, currentUserId);
        return ResponseEntity.ok(ApiResponse.ok("Gmail profile retrieved successfully", profile));
    }

    /**
     * GET /api/v1/accounts/{accountId}/gmail/messages
     *
     * <p>Lists Gmail message references (id + threadId) for the specified connected account.
     * Supports cursor-based pagination via {@code pageToken}.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code pageSize} — number of messages per page (default: 20, max: 100)</li>
     *   <li>{@code pageToken} — pagination cursor from a previous response (omit for first page)</li>
     * </ul>
     *
     * <p>Sample response:
     * <pre>
     * {
     *   "status": 200,
     *   "message": "Gmail messages retrieved successfully",
     *   "data": {
     *     "accountId": "...",
     *     "messages": [
     *       { "messageId": "18cd...", "threadId": "18cd..." },
     *       ...
     *     ],
     *     "nextPageToken": "04312...",
     *     "resultSizeEstimate": 1543,
     *     "pageSize": 20
     *   }
     * }
     * </pre>
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<GmailMessageListResponse>> listMessages(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false)    String pageToken
    ) {
        // Silently clamp pageSize to valid range to provide a good developer experience
        int clampedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        GmailMessageListResponse result = gmailService.listMessages(
                accountId, currentUserId, clampedPageSize, pageToken);
        return ResponseEntity.ok(ApiResponse.ok("Gmail messages retrieved successfully", result));
    }
}
