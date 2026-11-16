package com.inboxiq.account.gmail.dto;

import com.inboxiq.account.gmail.model.GmailProfile;

import java.util.UUID;

/**
 * Sanitized API response for GET /api/v1/accounts/{accountId}/gmail/profile.
 * Never contains OAuth tokens, encryption material, or internal IDs beyond the accountId.
 */
public record GmailProfileResponse(
        UUID accountId,
        String gmailAddress,
        Long messagesTotal,
        Long threadsTotal,
        String historyId
) {
    public static GmailProfileResponse from(UUID accountId, GmailProfile profile) {
        return new GmailProfileResponse(
                accountId,
                profile.emailAddress(),
                profile.messagesTotal(),
                profile.threadsTotal(),
                profile.historyId()
        );
    }
}
