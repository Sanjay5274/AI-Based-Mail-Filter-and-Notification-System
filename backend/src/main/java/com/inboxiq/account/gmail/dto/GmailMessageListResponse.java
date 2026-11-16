package com.inboxiq.account.gmail.dto;

import com.inboxiq.account.gmail.model.GmailMessageRef;

import java.util.List;
import java.util.UUID;

/**
 * Sanitized API response for GET /api/v1/accounts/{accountId}/gmail/messages.
 * Wraps the list of message references with pagination metadata.
 * Never contains OAuth tokens or raw Google API objects.
 */
public record GmailMessageListResponse(
        UUID accountId,
        List<GmailMessageSummary> messages,
        String nextPageToken,
        Integer resultSizeEstimate,
        int pageSize
) {
    public record GmailMessageSummary(String messageId, String threadId) {
        public static GmailMessageSummary from(GmailMessageRef ref) {
            return new GmailMessageSummary(ref.id(), ref.threadId());
        }
    }
}
