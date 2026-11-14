package com.inboxiq.account;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.model.GoogleTokenResponse;
import com.inboxiq.account.model.GoogleUserInfo;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.GoogleOAuthClient;
import com.inboxiq.account.service.GoogleOAuthService;
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

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectedAccountRepository connectedAccountRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private GoogleOAuthService googleOAuthService;

    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;

    private User testUser;
    private String jwtToken;

    @BeforeEach
    void setupUser() {
        testUser = new User("oauth_tester@inboxiq.local", "hashed_pwd", "OAuth Tester", null);
        testUser = userRepository.save(testUser);
        jwtToken = jwtTokenProvider.generateAccessToken(testUser);
    }

    @Test
    @DisplayName("GET /api/v1/accounts: returns user connected accounts and omits secrets")
    void testListAccounts() throws Exception {
        ConnectedAccount account = new ConnectedAccount(
                testUser.getId(), "GOOGLE", "sub-101", "tester@gmail.com", "Tester",
                "enc-ciphertext", "v1", "nonce-iv-val", "tag-val", new String[]{"gmail.readonly"}
        );
        connectedAccountRepository.save(account);

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].emailAddress").value("tester@gmail.com"))
                .andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data[0].refreshTokenCiphertext").doesNotExist())
                .andExpect(jsonPath("$.data[0].authTag").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/accounts/google/connect: generates valid authorization URL")
    void testInitiateGoogleConnect() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/google/connect")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/accounts/google/callback: completes flow with mocked Google tokens")
    void testGoogleCallbackFlow() throws Exception {
        var authResp = googleOAuthService.createAuthorizationUrl(testUser.getId());
        String state = extractQueryParam(authResp.getAuthorizationUrl(), "state");

        when(googleOAuthClient.exchangeCode(eq("code-xyz"), any(), any()))
                .thenReturn(new GoogleTokenResponse("access-tok", "refresh-tok", 3600L, null, "Bearer", null, null, null));
        when(googleOAuthClient.getUserInfo("access-tok"))
                .thenReturn(new GoogleUserInfo("g-sub-999", "newuser@gmail.com", "New Gmail User", null, true));

        mockMvc.perform(get("/api/v1/accounts/google/callback")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("code", "code-xyz")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailAddress").value("newuser@gmail.com"))
                .andExpect(jsonPath("$.data.syncStatus").value("IDLE"));

        ConnectedAccount saved = connectedAccountRepository.findByProviderAndProviderAccountId("GOOGLE", "g-sub-999").orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(testUser.getId());
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("DELETE /api/v1/accounts/{accountId}: disconnects account and invokes remote revoke")
    void testDisconnectAccount() throws Exception {
        ConnectedAccount account = new ConnectedAccount(
                testUser.getId(), "GOOGLE", "sub-202", "tester2@gmail.com", "Tester Two",
                "enc-ciphertext", "v1", "nonce-iv-val", "tag-val", new String[]{"gmail.readonly"}
        );
        account = connectedAccountRepository.save(account);

        when(googleOAuthClient.revokeToken(any())).thenReturn(true);

        mockMvc.perform(delete("/api/v1/accounts/" + account.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locallyDeactivated").value(true));

        ConnectedAccount updated = connectedAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.isActive()).isFalse();
    }

    @Test
    @DisplayName("Cross-User Isolation: User A cannot delete User B's account (returns 404)")
    void testCrossUserAccountAccessDenied() throws Exception {
        User otherUser = new User("other@inboxiq.local", "hash", "Other User", null);
        otherUser = userRepository.save(otherUser);

        ConnectedAccount otherAccount = new ConnectedAccount(
                otherUser.getId(), "GOOGLE", "sub-303", "other@gmail.com", "Other",
                "enc-ciphertext", "v1", "nonce-iv-val", "tag-val", new String[]{"gmail.readonly"}
        );
        otherAccount = connectedAccountRepository.save(otherAccount);

        mockMvc.perform(delete("/api/v1/accounts/" + otherAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
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

