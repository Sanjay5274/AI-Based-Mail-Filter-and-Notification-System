package com.inboxiq.auth.service;

import com.inboxiq.auth.dto.AuthResponse;
import com.inboxiq.auth.dto.LoginRequest;
import com.inboxiq.auth.dto.RegisterRequest;
import com.inboxiq.auth.entity.AuthSession;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.UnauthorizedException;
import com.inboxiq.security.CookieUtils;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.dto.UserProfileResponse;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private CookieUtils cookieUtils;
    @Mock
    private HttpServletRequest httpRequest;
    @Mock
    private HttpServletResponse httpResponse;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenService,
                cookieUtils
        );
    }

    @Test
    @DisplayName("Should successfully register a new user with hashed password")
    void shouldRegisterNewUser() {
        RegisterRequest request = new RegisterRequest("newuser@example.com", "Password123!", "New User", "+1234567890");

        when(userRepository.existsByEmailIgnoreCase("newuser@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("$2a$12$hashedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            u.setCreatedAt(Instant.now());
            return u;
        });

        UserProfileResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("newuser@example.com");
        assertThat(response.getFullName()).isEqualTo("New User");
        verify(passwordEncoder).encode("Password123!");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should reject registration when email already exists")
    void shouldRejectDuplicateEmailRegistration() {
        RegisterRequest request = new RegisterRequest("existing@example.com", "Password123!", "Existing User", null);
        when(userRepository.existsByEmailIgnoreCase("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should login successfully with valid credentials and return auth response")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest("user@example.com", "Password123!");
        User user = new User("user@example.com", "$2a$12$hashedPassword", "Test User", null);
        user.setId(UUID.randomUUID());
        user.setActive(true);

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$12$hashedPassword")).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(httpRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

        when(jwtTokenProvider.generateAccessToken(user)).thenReturn("mock.jwt.token");
        when(jwtTokenProvider.getAccessTokenExpirationMs()).thenReturn(900000L);

        AuthSession session = new AuthSession(user.getId(), "hash", UUID.randomUUID(), Instant.now().plusSeconds(3600), "127.0.0.1", "Mozilla/5.0");
        when(refreshTokenService.createSession(eq(user.getId()), any(), any()))
                .thenReturn(new RefreshTokenService.RefreshTokenResult("raw.refresh.token", session));

        when(cookieUtils.createAccessTokenCookie(any())).thenReturn(ResponseCookie.from("accessToken", "val").build());
        when(cookieUtils.createRefreshTokenCookie(any())).thenReturn(ResponseCookie.from("refreshToken", "val").build());
        when(cookieUtils.createCsrfCookie(any())).thenReturn(ResponseCookie.from("XSRF-TOKEN", "val").build());

        AuthResponse response = authService.login(request, httpRequest, httpResponse);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.getUser().getEmail()).isEqualTo("user@example.com");
        verify(userRepository).save(user);
        verify(httpResponse, times(3)).addHeader(eq("Set-Cookie"), any());
    }

    @Test
    @DisplayName("Should reject login with wrong password")
    void shouldRejectLoginWithWrongPassword() {
        LoginRequest request = new LoginRequest("user@example.com", "WrongPassword!");
        User user = new User("user@example.com", "$2a$12$hashedPassword", "Test User", null);
        user.setId(UUID.randomUUID());
        user.setActive(true);

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword!", "$2a$12$hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, httpRequest, httpResponse))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    @DisplayName("Should reject login for non-existent email")
    void shouldRejectLoginWithNonExistentEmail() {
        LoginRequest request = new LoginRequest("unknown@example.com", "Password123!");
        when(userRepository.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, httpRequest, httpResponse))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    @DisplayName("Should clear cookies and revoke session on logout")
    void shouldClearCookiesOnLogout() {
        when(cookieUtils.clearAccessTokenCookie()).thenReturn(ResponseCookie.from("accessToken", "").build());
        when(cookieUtils.clearRefreshTokenCookie()).thenReturn(ResponseCookie.from("refreshToken", "").build());
        when(cookieUtils.clearCsrfCookie()).thenReturn(ResponseCookie.from("XSRF-TOKEN", "").build());

        authService.logout(httpRequest, httpResponse);

        verify(httpResponse, times(3)).addHeader(eq("Set-Cookie"), any());
    }
}
