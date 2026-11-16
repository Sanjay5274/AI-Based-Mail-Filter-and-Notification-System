package com.inboxiq.account.gmail;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailMessageRef;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.model.GoogleRefreshTokenResponse;
import com.inboxiq.account.gmail.service.GmailApiClient;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.TokenEncryptionService;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
@DisplayName("Gmail Controller Integration Tests")
class GmailControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectedAccountRepository connectedAccountRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private GmailApiClient gmailApiClient;

    @Autowired
    private TokenEncryptionService tokenEncryptionService;

    private User testUser;
    private String jwtToken;
    private ConnectedAccount testAccount;

    // We use a valid encryption key known at test time so we can store real encrypted access tokens
    private static final String FAKE_ACCESS_TOKEN = "ya29.test_access_token";

    @BeforeEach
    void setUp() {
        testUser = new User("gmail_tester@inboxiq.local", "hashed_password", "Gmail Tester", null);
        testUser = userRepository.save(testUser);
        jwtToken = jwtTokenProvider.generateAccessToken(testUser);

        // Build a connected account with a real encrypted refresh token
        UUID accountId = UUID.randomUUID();
        EncryptedTokenResult encRefresh = tokenEncryptionService.encrypt(
                "1//refresh_token_value", accountId, testUser.getId());

        testAccount = new ConnectedAccount(
                accountId,
                testUser.getId(),
                "GOOGLE",
                "google-sub-gmail-test",
                "gmail_tester@gmail.com",
                "Gmail Tester",
                encRefresh.ciphertext(),
                encRefresh.keyVersion(),
                encRefresh.nonceIv(),
                encRefresh.authTag(),
                new String[]{"https://www.googleapis.com/auth/gmail.readonly"}
        );

        // Store a valid encrypted access token (expires in 10 minutes)
        EncryptedTokenResult encAccess = tokenEncryptionService.encrypt(
                FAKE_ACCESS_TOKEN, accountId, testUser.getId());
        testAccount.setAccessTokenCiphertext(encAccess.ciphertext());
        testAccount.setAccessTokenKeyVersion(encAccess.keyVersion());
        testAccount.setAccessTokenNonceIv(encAccess.nonceIv());
        testAccount.setAccessTokenAuthTag(encAccess.authTag());
        testAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(600));

        testAccount = connectedAccountRepository.save(testAccount);
    }

    // ─── GET /gmail/profile ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /gmail/profile: returns sanitized profile response")
    void getProfile_success_returnsSanitizedResponse() throws Exception {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("gmail_tester@gmail.com", 5000L, 3000L, "11111"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(testAccount.getId().toString()))
                .andExpect(jsonPath("$.data.gmailAddress").value("gmail_tester@gmail.com"))
                .andExpect(jsonPath("$.data.messagesTotal").value(5000))
                .andExpect(jsonPath("$.data.historyId").value("11111"))
                // Ensure no token material is in the response
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.ciphertext").doesNotExist());
    }

    @Test
    @DisplayName("GET /gmail/profile: access token refresh flow on expired token")
    void getProfile_expiredToken_refreshesAndReturnsProfile() throws Exception {
        // Override the account with an expired access token
        testAccount.setAccessTokenExpiresAt(Instant.now().minusSeconds(60));
        connectedAccountRepository.save(testAccount);

        // The refresh call will be made using the stored refresh token
        when(gmailApiClient.refreshAccessToken(eq("1//refresh_token_value"), any(), any()))
                .thenReturn(new GoogleRefreshTokenResponse("new_access_token", 3600L, "Bearer", null, null, null));
        when(gmailApiClient.getProfile("new_access_token"))
                .thenReturn(new GmailProfile("gmail_tester@gmail.com", 5000L, 3000L, "22222"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyId").value("22222"));
    }

    @Test
    @DisplayName("GET /gmail/profile: revoked credentials returns 401 GOOGLE_CREDENTIALS_REVOKED")
    void getProfile_revokedCredentials_returns401() throws Exception {
        // Expire access token so refresh is attempted
        testAccount.setAccessTokenExpiresAt(Instant.now().minusSeconds(60));
        connectedAccountRepository.save(testAccount);

        when(gmailApiClient.refreshAccessToken(any(), any(), any()))
                .thenThrow(new com.inboxiq.common.exception.GoogleCredentialsRevokedException(
                        "Refresh token has been revoked"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("GOOGLE_CREDENTIALS_REVOKED"));
    }

    @Test
    @DisplayName("GET /gmail/profile: Gmail API rate limit returns 429")
    void getProfile_gmailRateLimit_returns429() throws Exception {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenThrow(new com.inboxiq.common.exception.GmailApiException(
                        "Rate limit exceeded", 429, "GMAIL_RATE_LIMITED"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("GMAIL_RATE_LIMITED"));
    }

    @Test
    @DisplayName("GET /gmail/profile: Gmail API server error returns 503")
    void getProfile_gmailServerError_returns503() throws Exception {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenThrow(new com.inboxiq.common.exception.GmailApiException(
                        "Service unavailable", 503, "GMAIL_SERVER_ERROR"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("GMAIL_SERVER_ERROR"));
    }

    @Test
    @DisplayName("GET /gmail/profile: cross-user account access returns 404")
    void getProfile_crossUserAccess_returns404() throws Exception {
        User otherUser = new User("other_gmail@inboxiq.local", "hash", "Other User", null);
        otherUser = userRepository.save(otherUser);

        ConnectedAccount otherAccount = new ConnectedAccount(
                otherUser.getId(), "GOOGLE", "other-sub", "other@gmail.com", "Other",
                "enc", "v1", "nonce", "tag",
                new String[]{"gmail.readonly"}
        );
        otherAccount = connectedAccountRepository.save(otherAccount);

        // testUser tries to access otherUser's account
        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", otherAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /gmail/profile: unauthenticated request returns 401")
    void getProfile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/profile", testAccount.getId()))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /gmail/messages ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /gmail/messages: returns sanitized message list")
    void listMessages_success_returnsSanitizedList() throws Exception {
        List<GmailMessageRef> refs = List.of(
                new GmailMessageRef("msg-aaa", "thread-aaa"),
                new GmailMessageRef("msg-bbb", "thread-bbb")
        );
        when(gmailApiClient.listMessages(FAKE_ACCESS_TOKEN, 20, null))
                .thenReturn(new GmailMessageListPage(refs, "token-next-page", 2500));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/messages", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(testAccount.getId().toString()))
                .andExpect(jsonPath("$.data.messages").isArray())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].messageId").value("msg-aaa"))
                .andExpect(jsonPath("$.data.messages[0].threadId").value("thread-aaa"))
                .andExpect(jsonPath("$.data.nextPageToken").value("token-next-page"))
                .andExpect(jsonPath("$.data.resultSizeEstimate").value(2500))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                // Ensure no token material is in the response
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("GET /gmail/messages: pagination with pageToken parameter")
    void listMessages_withPageToken_passesTokenToGmailApi() throws Exception {
        when(gmailApiClient.listMessages(FAKE_ACCESS_TOKEN, 10, "my-cursor"))
                .thenReturn(new GmailMessageListPage(List.of(), null, 0));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/messages", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("pageSize", "10")
                        .param("pageToken", "my-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isArray())
                .andExpect(jsonPath("$.data.pageSize").value(10));
    }

    @Test
    @DisplayName("GET /gmail/messages: oversized pageSize is clamped to 100")
    void listMessages_oversizedPageSize_clampedTo100() throws Exception {
        when(gmailApiClient.listMessages(FAKE_ACCESS_TOKEN, 100, null))
                .thenReturn(new GmailMessageListPage(List.of(), null, 0));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/messages", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("pageSize", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100));
    }

    @Test
    @DisplayName("GET /gmail/messages: cross-user account access returns 404")
    void listMessages_crossUserAccess_returns404() throws Exception {
        User anotherUser = new User("another@inboxiq.local", "hash", "Another User", null);
        anotherUser = userRepository.save(anotherUser);

        ConnectedAccount anotherAccount = new ConnectedAccount(
                anotherUser.getId(), "GOOGLE", "another-sub", "another@gmail.com", "Another",
                "enc", "v1", "nonce", "tag",
                new String[]{"gmail.readonly"}
        );
        anotherAccount = connectedAccountRepository.save(anotherAccount);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/gmail/messages", anotherAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }
}
