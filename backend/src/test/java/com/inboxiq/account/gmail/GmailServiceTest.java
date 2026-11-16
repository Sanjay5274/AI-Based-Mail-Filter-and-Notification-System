package com.inboxiq.account.gmail;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.dto.GmailMessageListResponse;
import com.inboxiq.account.gmail.dto.GmailProfileResponse;
import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailMessageRef;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.model.GoogleRefreshTokenResponse;
import com.inboxiq.account.gmail.service.GmailApiClient;
import com.inboxiq.account.gmail.service.GmailService;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.TokenEncryptionService;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.common.exception.GmailApiException;
import com.inboxiq.common.exception.GoogleCredentialsRevokedException;
import com.inboxiq.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GmailService Unit Tests")
class GmailServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    @Mock
    private GmailApiClient gmailApiClient;

    private GmailService gmailService;

    private UUID userId;
    private UUID accountId;
    private ConnectedAccount activeAccount;

    private static final String FAKE_ENCRYPTED = "enc-ciphertext";
    private static final String FAKE_NONCE     = "nonce-iv";
    private static final String FAKE_TAG       = "auth-tag";
    private static final String FAKE_KEY_VER   = "v1";
    private static final String FAKE_ACCESS    = "ya29.access_token";
    private static final String FAKE_REFRESH   = "1//refresh_token";

    @BeforeEach
    void setUp() {
        gmailService = new GmailService(
                connectedAccountRepository,
                tokenEncryptionService,
                gmailApiClient,
                "test-client-id",
                "test-client-secret"
        );

        userId    = UUID.randomUUID();
        accountId = UUID.randomUUID();

        activeAccount = buildActiveAccount(accountId, userId);
    }

    // ─── getProfile ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProfile: returns sanitized profile when access token is valid")
    void getProfile_validAccessToken_returnsProfile() {
        // Access token is NOT expired
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(FAKE_ENCRYPTED, FAKE_NONCE, FAKE_TAG, FAKE_KEY_VER, accountId, userId))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.getProfile(FAKE_ACCESS))
                .thenReturn(new GmailProfile("user@gmail.com", 1500L, 900L, "12345"));

        GmailProfileResponse response = gmailService.getProfile(accountId, userId);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.gmailAddress()).isEqualTo("user@gmail.com");
        assertThat(response.messagesTotal()).isEqualTo(1500L);
        assertThat(response.historyId()).isEqualTo("12345");
        // Verify refresh was NOT called (token was valid)
        verify(gmailApiClient, never()).refreshAccessToken(any(), any(), any());
    }

    @Test
    @DisplayName("getProfile: refreshes expired access token then retrieves profile")
    void getProfile_expiredAccessToken_refreshesAndRetrieves() {
        // Access token is expired
        activeAccount.setAccessTokenExpiresAt(Instant.now().minusSeconds(60));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        // Refresh token decryption
        when(tokenEncryptionService.decrypt(FAKE_ENCRYPTED, FAKE_NONCE, FAKE_TAG, FAKE_KEY_VER, accountId, userId))
                .thenReturn(FAKE_REFRESH);
        // Token refresh returns new access token
        when(gmailApiClient.refreshAccessToken(FAKE_REFRESH, "test-client-id", "test-client-secret"))
                .thenReturn(new GoogleRefreshTokenResponse("new_access_token", 3600L, "Bearer", null, null, null));
        // Encrypt new access token
        when(tokenEncryptionService.encrypt(eq("new_access_token"), eq(accountId), eq(userId)))
                .thenReturn(new EncryptedTokenResult("new-cipher", "new-nonce", "new-tag", "v1"));
        when(connectedAccountRepository.save(any())).thenReturn(activeAccount);
        when(gmailApiClient.getProfile("new_access_token"))
                .thenReturn(new GmailProfile("user@gmail.com", 100L, 50L, "99999"));

        GmailProfileResponse response = gmailService.getProfile(accountId, userId);

        assertThat(response.gmailAddress()).isEqualTo("user@gmail.com");
        verify(gmailApiClient).refreshAccessToken(FAKE_REFRESH, "test-client-id", "test-client-secret");
        verify(connectedAccountRepository).save(activeAccount);
    }

    @Test
    @DisplayName("getProfile: cross-user access returns 404 (ResourceNotFoundException)")
    void getProfile_crossUserAccess_throws404() {
        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> gmailService.getProfile(accountId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(accountId.toString());

        verifyNoInteractions(gmailApiClient);
    }

    @Test
    @DisplayName("getProfile: inactive account returns 404")
    void getProfile_inactiveAccount_throws404() {
        activeAccount.setActive(false);
        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));

        assertThatThrownBy(() -> gmailService.getProfile(accountId, userId))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(gmailApiClient);
    }

    @Test
    @DisplayName("getProfile: revoked refresh token deactivates account and throws GoogleCredentialsRevokedException")
    void getProfile_revokedRefreshToken_deactivatesAccountAndThrows() {
        // No valid access token
        activeAccount.setAccessTokenExpiresAt(null);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        // Refresh token decryption works
        when(tokenEncryptionService.decrypt(FAKE_ENCRYPTED, FAKE_NONCE, FAKE_TAG, FAKE_KEY_VER, accountId, userId))
                .thenReturn(FAKE_REFRESH);
        // But Google says the refresh token is revoked
        when(gmailApiClient.refreshAccessToken(FAKE_REFRESH, "test-client-id", "test-client-secret"))
                .thenThrow(new GoogleCredentialsRevokedException("Refresh token revoked by user"));
        when(connectedAccountRepository.save(any())).thenReturn(activeAccount);

        assertThatThrownBy(() -> gmailService.getProfile(accountId, userId))
                .isInstanceOf(GoogleCredentialsRevokedException.class);

        assertThat(activeAccount.isActive()).isFalse();
        assertThat(activeAccount.getSyncStatus()).isEqualTo("FAILED");
        verify(connectedAccountRepository).save(activeAccount);
    }

    @Test
    @DisplayName("getProfile: Gmail API rate limit throws GmailApiException with httpStatus 429")
    void getProfile_gmailRateLimit_throwsGmailApiException() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.getProfile(FAKE_ACCESS))
                .thenThrow(new GmailApiException("Rate limited", 429, "GMAIL_RATE_LIMITED"));

        assertThatThrownBy(() -> gmailService.getProfile(accountId, userId))
                .isInstanceOf(GmailApiException.class)
                .satisfies(ex -> assertThat(((GmailApiException) ex).getHttpStatus()).isEqualTo(429));
    }

    @Test
    @DisplayName("getProfile: Gmail API 5xx throws GmailApiException")
    void getProfile_gmailServerError_throwsGmailApiException() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.getProfile(FAKE_ACCESS))
                .thenThrow(new GmailApiException("Service unavailable", 503, "GMAIL_SERVER_ERROR"));

        assertThatThrownBy(() -> gmailService.getProfile(accountId, userId))
                .isInstanceOf(GmailApiException.class)
                .satisfies(ex -> assertThat(((GmailApiException) ex).getHttpStatus()).isEqualTo(503));
    }

    // ─── listMessages ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("listMessages: returns sanitized message list with correct pagination metadata")
    void listMessages_success_returnsSanitizedResponse() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);

        List<GmailMessageRef> refs = List.of(
                new GmailMessageRef("msg-1", "thread-1"),
                new GmailMessageRef("msg-2", "thread-2")
        );
        when(gmailApiClient.listMessages(FAKE_ACCESS, 20, null))
                .thenReturn(new GmailMessageListPage(refs, "next-page-token-xyz", 1543));

        GmailMessageListResponse response = gmailService.listMessages(accountId, userId, 20, null);

        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.messages()).hasSize(2);
        assertThat(response.messages().get(0).messageId()).isEqualTo("msg-1");
        assertThat(response.messages().get(1).threadId()).isEqualTo("thread-2");
        assertThat(response.nextPageToken()).isEqualTo("next-page-token-xyz");
        assertThat(response.resultSizeEstimate()).isEqualTo(1543);
        assertThat(response.pageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("listMessages: pagination with valid pageToken passes it through to Gmail API")
    void listMessages_withPageToken_passesThroughToGmailApi() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.listMessages(FAKE_ACCESS, 10, "cursor-abc"))
                .thenReturn(new GmailMessageListPage(List.of(), null, 10));

        GmailMessageListResponse response = gmailService.listMessages(accountId, userId, 10, "cursor-abc");

        assertThat(response.nextPageToken()).isNull();
        assertThat(response.messages()).isEmpty();
        verify(gmailApiClient).listMessages(FAKE_ACCESS, 10, "cursor-abc");
    }

    @Test
    @DisplayName("listMessages: pageSize is clamped to MAX_PAGE_SIZE (100)")
    void listMessages_oversizedPageSize_isClamped() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS), eq(100), isNull()))
                .thenReturn(new GmailMessageListPage(List.of(), null, 0));

        GmailMessageListResponse response = gmailService.listMessages(accountId, userId, 9999, null);

        assertThat(response.pageSize()).isEqualTo(100);
        verify(gmailApiClient).listMessages(FAKE_ACCESS, 100, null);
    }

    @Test
    @DisplayName("listMessages: empty page token is treated as null (first page)")
    void listMessages_emptyPageToken_treatedAsNull() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        when(gmailApiClient.listMessages(FAKE_ACCESS, 20, null))
                .thenReturn(new GmailMessageListPage(List.of(), null, 0));

        gmailService.listMessages(accountId, userId, 20, "   ");
        verify(gmailApiClient).listMessages(FAKE_ACCESS, 20, null);
    }

    @Test
    @DisplayName("listMessages: empty message list from Google is handled gracefully")
    void listMessages_emptyGmailResponse_returnsEmptyList() {
        activeAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));
        activeAccount.setAccessTokenCiphertext(FAKE_ENCRYPTED);
        activeAccount.setAccessTokenNonceIv(FAKE_NONCE);
        activeAccount.setAccessTokenAuthTag(FAKE_TAG);
        activeAccount.setAccessTokenKeyVersion(FAKE_KEY_VER);

        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(activeAccount));
        when(tokenEncryptionService.decrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn(FAKE_ACCESS);
        // Google returns null messages list when mailbox is empty
        when(gmailApiClient.listMessages(FAKE_ACCESS, 20, null))
                .thenReturn(new GmailMessageListPage(null, null, 0));

        GmailMessageListResponse response = gmailService.listMessages(accountId, userId, 20, null);

        assertThat(response.messages()).isEmpty();
        assertThat(response.nextPageToken()).isNull();
    }

    @Test
    @DisplayName("listMessages: cross-user access returns 404")
    void listMessages_crossUserAccess_throws404() {
        when(connectedAccountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> gmailService.listMessages(accountId, userId, 20, null))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(gmailApiClient);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ConnectedAccount buildActiveAccount(UUID id, UUID ownerId) {
        ConnectedAccount account = new ConnectedAccount(
                id, ownerId, "GOOGLE", "g-sub-123", "user@gmail.com", "Test User",
                FAKE_ENCRYPTED, FAKE_KEY_VER, FAKE_NONCE, FAKE_TAG,
                new String[]{"https://www.googleapis.com/auth/gmail.readonly"}
        );
        account.setActive(true);
        return account;
    }
}
