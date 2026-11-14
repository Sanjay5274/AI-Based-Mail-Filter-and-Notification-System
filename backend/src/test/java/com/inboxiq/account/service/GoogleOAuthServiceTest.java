package com.inboxiq.account.service;

import com.inboxiq.account.dto.AccountResponse;
import com.inboxiq.account.dto.OAuthUrlResponse;
import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.account.model.GoogleTokenResponse;
import com.inboxiq.account.model.GoogleUserInfo;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.common.exception.BadRequestException;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    @Mock
    private GoogleOAuthClient googleOAuthClient;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    private GoogleOAuthService googleOAuthService;

    private final String clientId = "test-client-id";
    private final String redirectUri = "http://localhost:8081/api/v1/accounts/google/callback";
    private final String scope = "https://www.googleapis.com/auth/gmail.readonly";

    @BeforeEach
    void setUp() {
        googleOAuthService = new GoogleOAuthService(
                clientId,
                redirectUri,
                scope,
                googleOAuthClient,
                tokenEncryptionService,
                connectedAccountRepository
        );
    }

    @Test
    @DisplayName("OAuth URL Generation: includes client_id, scope, offline access, state, and PKCE challenge")
    void testCreateAuthorizationUrl() {
        UUID userId = UUID.randomUUID();

        OAuthUrlResponse response = googleOAuthService.createAuthorizationUrl(userId);

        assertThat(response).isNotNull();
        String url = response.getAuthorizationUrl();
        assertThat(url).contains("client_id=" + clientId);
        assertThat(url).contains("redirect_uri=" + redirectUri);
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("code_challenge=");
        assertThat(url).contains("code_challenge_method=S256");
        assertThat(url).contains("state=");
    }

    @Test
    @DisplayName("Callback: successful initial connection encrypts token and saves account")
    void testSuccessfulInitialConnection() {
        UUID userId = UUID.randomUUID();
        OAuthUrlResponse authUrlResp = googleOAuthService.createAuthorizationUrl(userId);
        String state = extractQueryParam(authUrlResp.getAuthorizationUrl(), "state");

        GoogleTokenResponse tokenResp = new GoogleTokenResponse(
                "mock-access-token", "mock-refresh-token", 3600L, "id-tok", "Bearer", "scope", null, null
        );
        GoogleUserInfo userInfo = new GoogleUserInfo(
                "google-sub-12345", "user@gmail.com", "Gmail User", null, true
        );

        when(googleOAuthClient.exchangeCode(eq("auth-code"), any(), eq(redirectUri))).thenReturn(tokenResp);
        when(googleOAuthClient.getUserInfo("mock-access-token")).thenReturn(userInfo);
        when(connectedAccountRepository.findByProviderAndProviderAccountId("GOOGLE", "google-sub-12345"))
                .thenReturn(Optional.empty());

        EncryptedTokenResult encResult = new EncryptedTokenResult("cipherHex", "ivHex", "tagHex", "v1");
        when(tokenEncryptionService.encrypt(eq("mock-refresh-token"), any(UUID.class), eq(userId)))
                .thenReturn(encResult);

        when(connectedAccountRepository.save(any(ConnectedAccount.class))).thenAnswer(inv -> {
            ConnectedAccount acc = inv.getArgument(0);
            return acc;
        });

        AccountResponse result = googleOAuthService.handleCallback("auth-code", state, userId);

        assertThat(result).isNotNull();
        assertThat(result.getEmailAddress()).isEqualTo("user@gmail.com");
        assertThat(result.getDisplayName()).isEqualTo("Gmail User");
        assertThat(result.getProvider()).isEqualTo("GOOGLE");

        ArgumentCaptor<ConnectedAccount> accountCaptor = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(connectedAccountRepository).save(accountCaptor.capture());
        ConnectedAccount saved = accountCaptor.getValue();
        assertThat(saved.getRefreshTokenCiphertext()).isEqualTo("cipherHex");
        assertThat(saved.getNonceIv()).isEqualTo("ivHex");
        assertThat(saved.getAuthTag()).isEqualTo("tagHex");
        assertThat(saved.getProviderAccountId()).isEqualTo("google-sub-12345");
    }

    @Test
    @DisplayName("Callback: state replay attack is rejected with UnauthorizedException")
    void testStateReplayRejected() {
        UUID userId = UUID.randomUUID();
        OAuthUrlResponse authUrlResp = googleOAuthService.createAuthorizationUrl(userId);
        String state = extractQueryParam(authUrlResp.getAuthorizationUrl(), "state");

        GoogleTokenResponse tokenResp = new GoogleTokenResponse("at", "rt", 3600L, null, "Bearer", null, null, null);
        GoogleUserInfo userInfo = new GoogleUserInfo("sub", "email@gmail.com", "Name", null, true);

        when(googleOAuthClient.exchangeCode(any(), any(), any())).thenReturn(tokenResp);
        when(googleOAuthClient.getUserInfo(any())).thenReturn(userInfo);
        when(connectedAccountRepository.findByProviderAndProviderAccountId(any(), any())).thenReturn(Optional.empty());
        when(tokenEncryptionService.encrypt(any(), any(), any())).thenReturn(new EncryptedTokenResult("c", "i", "t", "v1"));
        when(connectedAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // First use succeeds
        googleOAuthService.handleCallback("code1", state, userId);

        // Second use with the same state must be rejected
        assertThatThrownBy(() -> googleOAuthService.handleCallback("code2", state, userId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid, expired, or already-used");
    }

    @Test
    @DisplayName("Callback: state belonging to another user is rejected with UnauthorizedException")
    void testStateUserMismatchRejected() {
        UUID originalUser = UUID.randomUUID();
        UUID attackerUser = UUID.randomUUID();

        OAuthUrlResponse authUrlResp = googleOAuthService.createAuthorizationUrl(originalUser);
        String state = extractQueryParam(authUrlResp.getAuthorizationUrl(), "state");

        assertThatThrownBy(() -> googleOAuthService.handleCallback("code", state, attackerUser))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("Callback: connecting account already owned by another user throws ConflictException")
    void testCrossUserAccountCollisionRejected() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        OAuthUrlResponse authUrlResp = googleOAuthService.createAuthorizationUrl(userB);
        String state = extractQueryParam(authUrlResp.getAuthorizationUrl(), "state");

        GoogleTokenResponse tokenResp = new GoogleTokenResponse("at", "rt", 3600L, null, "Bearer", null, null, null);
        GoogleUserInfo userInfo = new GoogleUserInfo("shared-google-sub", "shared@gmail.com", "User", null, true);

        when(googleOAuthClient.exchangeCode(any(), any(), any())).thenReturn(tokenResp);
        when(googleOAuthClient.getUserInfo(any())).thenReturn(userInfo);

        // Account is already bound to User A
        ConnectedAccount existingAccountOfUserA = new ConnectedAccount();
        existingAccountOfUserA.setId(UUID.randomUUID());
        existingAccountOfUserA.setUserId(userA);
        existingAccountOfUserA.setProviderAccountId("shared-google-sub");

        when(connectedAccountRepository.findByProviderAndProviderAccountId("GOOGLE", "shared-google-sub"))
                .thenReturn(Optional.of(existingAccountOfUserA));

        assertThatThrownBy(() -> googleOAuthService.handleCallback("code", state, userB))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already connected to another InboxIQ user");
    }

    @Test
    @DisplayName("Callback: missing refresh token on initial connection throws BadRequestException")
    void testMissingRefreshTokenOnInitialConnectionRejected() {
        UUID userId = UUID.randomUUID();
        OAuthUrlResponse authUrlResp = googleOAuthService.createAuthorizationUrl(userId);
        String state = extractQueryParam(authUrlResp.getAuthorizationUrl(), "state");

        // Google returns no refresh token
        GoogleTokenResponse tokenResp = new GoogleTokenResponse("at", null, 3600L, null, "Bearer", null, null, null);
        GoogleUserInfo userInfo = new GoogleUserInfo("sub", "email@gmail.com", "Name", null, true);

        when(googleOAuthClient.exchangeCode(any(), any(), any())).thenReturn(tokenResp);
        when(googleOAuthClient.getUserInfo(any())).thenReturn(userInfo);
        when(connectedAccountRepository.findByProviderAndProviderAccountId("GOOGLE", "sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> googleOAuthService.handleCallback("code", state, userId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("did not provide a refresh token");
    }

    private String extractQueryParam(String url, String param) {
        Pattern pattern = Pattern.compile("[?&]" + param + "=([^&#]*)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Param " + param + " not found in URL: " + url);
    }
}

