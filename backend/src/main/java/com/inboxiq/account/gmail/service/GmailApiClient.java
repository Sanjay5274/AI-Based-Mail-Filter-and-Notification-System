package com.inboxiq.account.gmail.service;

import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.model.GoogleRefreshTokenResponse;

/**
 * Low-level Gmail API client interface.
 *
 * <p>All methods require a valid, non-expired access token. This interface does NOT
 * manage token lifecycle — that responsibility belongs to {@link GmailService}.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Never log Authorization headers or token values.</li>
 *   <li>Translate HTTP 401 to {@link com.inboxiq.common.exception.GoogleCredentialsRevokedException}.</li>
 *   <li>Translate HTTP 429 to {@link com.inboxiq.common.exception.GmailApiException} with httpStatus=429.</li>
 *   <li>Translate HTTP 5xx to {@link com.inboxiq.common.exception.GmailApiException} with appropriate code.</li>
 * </ul>
 */
public interface GmailApiClient {

    /**
     * Retrieves the authenticated Gmail user's mailbox profile.
     * Maps to: GET https://gmail.googleapis.com/gmail/v1/users/me/profile
     *
     * @param accessToken valid Google OAuth access token (never logged)
     * @return mailbox profile including emailAddress, historyId, message/thread counts
     */
    GmailProfile getProfile(String accessToken);

    /**
     * Lists message metadata (id + threadId) for the authenticated mailbox.
     * Maps to: GET https://gmail.googleapis.com/gmail/v1/users/me/messages
     *
     * @param accessToken   valid Google OAuth access token (never logged)
     * @param maxResults    maximum number of messages to return (1-500)
     * @param pageToken     optional pagination token from a previous response; null for first page
     * @return a page of message references and an optional nextPageToken
     */
    GmailMessageListPage listMessages(String accessToken, int maxResults, String pageToken);

    /**
     * Lists message metadata (id + threadId) for the authenticated mailbox with search query filter.
     * Maps to: GET https://gmail.googleapis.com/gmail/v1/users/me/messages?q={query}
     *
     * @param accessToken   valid Google OAuth access token (never logged)
     * @param maxResults    maximum number of messages to return (1-500)
     * @param pageToken     optional pagination token from a previous response; null for first page
     * @param query         optional search query (e.g. "after:1700000000"); null for all
     * @return a page of message references and an optional nextPageToken
     */
    GmailMessageListPage listMessages(String accessToken, int maxResults, String pageToken, String query);

    /**
     * Retrieves the full details of a specific message.
     * Maps to: GET https://gmail.googleapis.com/gmail/v1/users/me/messages/{id}?format={format}
     *
     * @param accessToken valid Google OAuth access token (never logged)
     * @param messageId   Gmail unique message ID
     * @param format      message format ("full", "minimal", "raw", "metadata")
     * @return parsed GmailMessage containing headers, payload, snippet, internalDate
     */
    com.inboxiq.account.gmail.model.GmailMessage getMessage(String accessToken, String messageId, String format);

    /**
     * Exchanges a refresh token for a new access token.
     * Maps to: POST https://oauth2.googleapis.com/token (grant_type=refresh_token)
     *
     * <p>Never log the refreshToken or the returned accessToken.
     *
     * @param refreshToken  plaintext refresh token (never logged)
     * @param clientId      OAuth client ID
     * @param clientSecret  OAuth client secret
     * @return new access token response including expiry
     */
    GoogleRefreshTokenResponse refreshAccessToken(String refreshToken, String clientId, String clientSecret);
}
