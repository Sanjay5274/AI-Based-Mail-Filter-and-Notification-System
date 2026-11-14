package com.inboxiq.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.auth.dto.LoginRequest;
import com.inboxiq.auth.dto.RegisterRequest;
import com.inboxiq.auth.entity.AuthSession;
import com.inboxiq.auth.repository.AuthSessionRepository;
import com.inboxiq.auth.service.RefreshTokenService;
import com.inboxiq.common.exception.TokenReuseException;
import com.inboxiq.security.CookieUtils;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RefreshTokenService refreshTokenService;

    private final String testEmail = "developer@inboxiq.local";
    private final String rawPassword = "StrongSecret123!";

    @BeforeEach
    void cleanState() {
        userRepository.findByEmailIgnoreCase(testEmail).ifPresent(user -> {
            authSessionRepository.deleteAll(authSessionRepository.findAllByUserIdAndRevokedAtIsNull(user.getId()));
            userRepository.delete(user);
        });
    }

    @Test
    @DisplayName("User Registration: successful registration with status 201")
    void testSuccessfulRegistration() throws Exception {
        RegisterRequest req = new RegisterRequest(testEmail, rawPassword, "Test Developer", "+14155552671");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.email").value(testEmail))
                .andExpect(jsonPath("$.data.fullName").value("Test Developer"));

        User saved = userRepository.findByEmailIgnoreCase(testEmail).orElseThrow();
        assertThat(passwordEncoder.matches(rawPassword, saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("User Registration: duplicate email returns 409 Conflict")
    void testDuplicateRegistration() throws Exception {
        User existing = new User(testEmail, passwordEncoder.encode(rawPassword), "Existing User", null);
        userRepository.save(existing);

        RegisterRequest req = new RegisterRequest(testEmail, rawPassword, "Another Name", null);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("User Login: successful login returns 200, JWT and cookies")
    void testSuccessfulLogin() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        userRepository.save(user);

        LoginRequest req = new LoginRequest(testEmail, rawPassword);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(cookie().exists(CookieUtils.ACCESS_TOKEN_COOKIE_NAME))
                .andExpect(cookie().exists(CookieUtils.REFRESH_TOKEN_COOKIE_NAME))
                .andExpect(cookie().exists(CookieUtils.CSRF_COOKIE_NAME));
    }

    @Test
    @DisplayName("User Login: invalid credentials return 401 Unauthorized")
    void testInvalidLoginCredentials() throws Exception {
        LoginRequest req = new LoginRequest("nonexistent@inboxiq.local", "WrongPassword123!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Protected Profile: authenticated user can access /api/v1/users/me")
    void testAccessProtectedProfileWithJwt() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        user = userRepository.save(user);

        String jwt = jwtTokenProvider.generateAccessToken(user);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")))
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value(testEmail));
    }

    @Test
    @DisplayName("Protected Profile: disabled or inactive user is rejected with 401")
    void testInactiveUserRejected() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Inactive User", null);
        user.setActive(false);
        user = userRepository.save(user);

        String jwt = jwtTokenProvider.generateAccessToken(user);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Protected Profile: unauthenticated access rejected with 401")
    void testUnauthorizedAccessRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF Protection: cookie-based refresh without CSRF token is rejected with 403")
    void testRefreshCookieWithoutCsrfRejected() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        user = userRepository.save(user);

        var sessionResult = refreshTokenService.createSession(user.getId(), "127.0.0.1", "JUnit5");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtils.REFRESH_TOKEN_COOKIE_NAME, sessionResult.rawToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Refresh Flow: rotating refresh token with CSRF token succeeds")
    void testRefreshTokenRotationFlowWithCsrf() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        user = userRepository.save(user);

        var sessionResult = refreshTokenService.createSession(user.getId(), "127.0.0.1", "JUnit5");
        String initialRefreshToken = sessionResult.rawToken();
        String csrfTokenValue = "csrf-secret-12345";

        var mvcResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtils.REFRESH_TOKEN_COOKIE_NAME, initialRefreshToken))
                        .cookie(new Cookie(CookieUtils.CSRF_COOKIE_NAME, csrfTokenValue))
                        .header("X-CSRF-Token", csrfTokenValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(cookie().exists(CookieUtils.ACCESS_TOKEN_COOKIE_NAME))
                .andExpect(cookie().exists(CookieUtils.REFRESH_TOKEN_COOKIE_NAME))
                .andReturn();

        Cookie rotatedCookie = mvcResult.getResponse().getCookie(CookieUtils.REFRESH_TOKEN_COOKIE_NAME);
        assertThat(rotatedCookie).isNotNull();
        String rotatedRefreshToken = rotatedCookie.getValue();
        assertThat(rotatedRefreshToken).isNotEqualTo(initialRefreshToken);

        // Replay of the already used initial token should be detected as theft and rejected
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtils.REFRESH_TOKEN_COOKIE_NAME, initialRefreshToken))
                        .cookie(new Cookie(CookieUtils.CSRF_COOKIE_NAME, csrfTokenValue))
                        .header("X-CSRF-Token", csrfTokenValue))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REUSE_DETECTED"));
    }

    @Test
    @DisplayName("Refresh Flow: non-browser client using X-Refresh-Token header succeeds")
    void testRefreshTokenWithCustomHeader() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        user = userRepository.save(user);

        var sessionResult = refreshTokenService.createSession(user.getId(), "127.0.0.1", "JUnit5");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("X-Refresh-Token", sessionResult.rawToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("User Logout: logout with valid bearer token clears session and cookies")
    void testLogoutWithBearerToken() throws Exception {
        User user = new User(testEmail, passwordEncoder.encode(rawPassword), "Test Developer", null);
        user = userRepository.save(user);

        var sessionResult = refreshTokenService.createSession(user.getId(), "127.0.0.1", "JUnit5");
        String jwt = jwtTokenProvider.generateAccessToken(user);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + jwt)
                        .cookie(new Cookie(CookieUtils.REFRESH_TOKEN_COOKIE_NAME, sessionResult.rawToken())))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(CookieUtils.ACCESS_TOKEN_COOKIE_NAME, 0))
                .andExpect(cookie().maxAge(CookieUtils.REFRESH_TOKEN_COOKIE_NAME, 0));

        // Verify session was revoked in database
        String tokenHash = RefreshTokenService.hashToken(sessionResult.rawToken());
        AuthSession session = authSessionRepository.findByTokenHash(tokenHash).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
    }
}
