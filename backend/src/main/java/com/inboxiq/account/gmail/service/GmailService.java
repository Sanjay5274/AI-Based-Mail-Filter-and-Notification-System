package com.inboxiq.account.gmail.service;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.dto.GmailMessageListResponse;
import com.inboxiq.account.gmail.dto.GmailProfileResponse;
import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.model.GoogleRefreshTokenResponse;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.TokenEncryptionService;
import com.inboxiq.account.gmail.model.GmailMessageRef;
import com.inboxiq.common.exception.DecryptionException;
import com.inboxiq.common.exception.GmailApiException;
import com.inboxiq.common.exception.GoogleCredentialsRevokedException;
import com.inboxiq.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates Gmail API operations for a user's connected account.
 *
 * <p><strong>Security invariants:</strong>
 * <ul>
 *   <li>User ID is always derived from the authenticated principal — never from request parameters.</li>
 *   <li>Account ownership is verified on every call before any Gmail API access.</li>
 *   <li>Tokens are decrypted only in memory when needed; references are not stored in fields.</li>
 *   <li>Access tokens are never exposed in logs, exceptions, or API responses.</li>
 *   <li>Refresh tokens are decrypted only when access tokens need refreshing.</li>
 * </ul>
 */
@Service
public class GmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailService.class);

    /** Refresh the access token if it expires within this window. */
    private static final Duration ACCESS_TOKEN_EXPIRY_LEEWAY = Duration.ofMinutes(5);

    /** Hard ceiling on page size to prevent large Google API responses. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Default page size when not specified by the caller. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final ConnectedAccountRepository connectedAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final GmailApiClient gmailApiClient;
    private final String googleClientId;
    private final String googleClientSecret;

    public GmailService(
            ConnectedAccountRepository connectedAccountRepository,
            TokenEncryptionService tokenEncryptionService,
            GmailApiClient gmailApiClient,
            @Value("${inboxiq.google.oauth.client-id}") String googleClientId,
            @Value("${inboxiq.google.oauth.client-secret}") String googleClientSecret
    ) {
        this.connectedAccountRepository = connectedAccountRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.gmailApiClient = gmailApiClient;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
    }

    /**
     * Retrieves the Gmail mailbox profile for the given connected account.
     *
     * @param accountId     the connected account to query
     * @param requestingUserId the authenticated user ID (from the JWT principal)
     */
    @Transactional
    public GmailProfileResponse getProfile(UUID accountId, UUID requestingUserId) {
        ConnectedAccount account = resolveOwnedActiveAccount(accountId, requestingUserId);
        String accessToken = resolveValidAccessToken(account);

        log.debug("Fetching Gmail profile for account {}", accountId);
        GmailProfile profile = gmailApiClient.getProfile(accessToken);

        return GmailProfileResponse.from(accountId, profile);
    }

    /**
     * Lists Gmail messages for the given connected account with pagination support.
     *
     * @param accountId        the connected account to query
     * @param requestingUserId the authenticated user ID (from the JWT principal)
     * @param pageSize         desired page size (clamped to 1–{@link #MAX_PAGE_SIZE})
     * @param pageToken        pagination continuation token (null = first page)
     */
    @Transactional
    public GmailMessageListResponse listMessages(
            UUID accountId,
            UUID requestingUserId,
            int pageSize,
            String pageToken
    ) {
        ConnectedAccount account = resolveOwnedActiveAccount(accountId, requestingUserId);

        // Clamp page size to safe range
        int clampedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        // Validate page token: null is fine (first page), empty string is treated as null
        String resolvedPageToken = (pageToken != null && pageToken.isBlank()) ? null : pageToken;

        String accessToken = resolveValidAccessToken(account);

        log.debug("Listing Gmail messages for account {} (pageSize={}, hasPageToken={})",
                accountId, clampedPageSize, resolvedPageToken != null);

        GmailMessageListPage page = gmailApiClient.listMessages(accessToken, clampedPageSize, resolvedPageToken);

        List<GmailMessageListResponse.GmailMessageSummary> summaries =
                page.messages() != null
                        ? page.messages().stream()
                              .map(GmailMessageListResponse.GmailMessageSummary::from)
                              .toList()
                        : Collections.emptyList();

        return new GmailMessageListResponse(
                accountId,
                summaries,
                page.nextPageToken(),
                page.resultSizeEstimate(),
                clampedPageSize
        );
    }

    // --- Internal helpers ---

    /**
     * Verifies the account exists, belongs to the requesting user, and is active.
     * Returns 404 (not 403) on cross-user access to avoid leaking account existence.
     */
    private ConnectedAccount resolveOwnedActiveAccount(UUID accountId, UUID requestingUserId) {
        ConnectedAccount account = connectedAccountRepository
                .findByIdAndUserId(accountId, requestingUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Connected account not found with ID: " + accountId));

        if (!account.isActive()) {
            throw new ResourceNotFoundException(
                    "Connected account not found with ID: " + accountId);
        }
        return account;
    }

    /**
     * Returns a valid plaintext access token for the given account.
     *
     * <p>If the stored access token is absent or within the expiry leeway window,
     * the refresh token is decrypted and used to obtain a new access token from Google.
     * The new token is then encrypted and persisted back to the database.
     *
     * <p>The plaintext token is returned as a local variable only — never stored as a field.
     */
    public String resolveValidAccessToken(ConnectedAccount account) {
        if (!account.isAccessTokenExpired(ACCESS_TOKEN_EXPIRY_LEEWAY)) {
            // Stored access token is still valid — decrypt and use it
            try {
                return tokenEncryptionService.decrypt(
                        account.getAccessTokenCiphertext(),
                        account.getAccessTokenNonceIv(),
                        account.getAccessTokenAuthTag(),
                        account.getAccessTokenKeyVersion(),
                        account.getId(),
                        account.getUserId()
                );
            } catch (DecryptionException e) {
                log.warn("Failed to decrypt stored access token for account {}; will refresh. Reason: {}",
                        account.getId(), e.getMessage());
                // Fall through to refresh
            }
        }

        // Access token missing/expired — decrypt refresh token and exchange for a new access token
        log.debug("Access token expired or missing for account {}; refreshing", account.getId());
        return refreshAndPersistAccessToken(account);
    }

    /**
     * Decrypts the refresh token, exchanges it for a new access token at Google,
     * encrypts the new access token, and persists updated fields to the database.
     *
     * @throws GoogleCredentialsRevokedException if Google rejects the refresh token
     * @throws GmailApiException                 on network or Google API errors
     */
    private String refreshAndPersistAccessToken(ConnectedAccount account) {
        String plainTextRefreshToken;
        try {
            plainTextRefreshToken = tokenEncryptionService.decrypt(
                    account.getRefreshTokenCiphertext(),
                    account.getNonceIv(),
                    account.getAuthTag(),
                    account.getEncryptionKeyVersion(),
                    account.getId(),
                    account.getUserId()
            );
        } catch (DecryptionException e) {
            log.error("Cannot decrypt refresh token for account {}. Credentials are unrecoverable.", account.getId());
            // Deactivate the account to prevent further sync attempts with corrupt credentials
            account.setActive(false);
            connectedAccountRepository.save(account);
            throw new GoogleCredentialsRevokedException(
                    "Stored credentials for the connected Gmail account are unrecoverable. " +
                    "Please reconnect your Google account.");
        }

        GoogleRefreshTokenResponse refreshResponse;
        try {
            refreshResponse = gmailApiClient.refreshAccessToken(
                    plainTextRefreshToken, googleClientId, googleClientSecret);
        } catch (GoogleCredentialsRevokedException e) {
            log.warn("Google credentials revoked for account {}. Deactivating account.", account.getId());
            account.setActive(false);
            account.setSyncStatus("FAILED");
            connectedAccountRepository.save(account);
            throw e;
        } finally {
            // Explicitly overwrite the plaintext refresh token reference with null to allow GC.
            // This is a best-effort measure; JVM GC timing is not guaranteed.
            plainTextRefreshToken = null;
        }

        String newAccessToken = refreshResponse.accessToken();
        long expiresInSeconds = refreshResponse.expiresIn() != null ? refreshResponse.expiresIn() : 3600L;
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);

        // Encrypt the new access token before storing
        var encResult = tokenEncryptionService.encrypt(newAccessToken, account.getId(), account.getUserId());

        account.setAccessTokenCiphertext(encResult.ciphertext());
        account.setAccessTokenKeyVersion(encResult.keyVersion());
        account.setAccessTokenNonceIv(encResult.nonceIv());
        account.setAccessTokenAuthTag(encResult.authTag());
        account.setAccessTokenExpiresAt(expiresAt);
        connectedAccountRepository.save(account);

        log.info("Successfully refreshed and persisted new access token for account {} (expires at {})",
                account.getId(), expiresAt);

        return newAccessToken;
    }
}
