package com.inboxiq.auth.service;

import com.inboxiq.auth.dto.AuthResponse;
import com.inboxiq.auth.dto.LoginRequest;
import com.inboxiq.auth.dto.RegisterRequest;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.UnauthorizedException;
import com.inboxiq.security.CookieUtils;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.dto.UserProfileResponse;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final CookieUtils cookieUtils;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenService refreshTokenService,
            CookieUtils cookieUtils
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.cookieUtils = cookieUtils;
    }

    @Transactional
    public UserProfileResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Email already registered: " + normalizedEmail);
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(
                normalizedEmail,
                hashedPassword,
                request.getFullName(),
                request.getPhoneNumber()
        );

        User savedUser = userRepository.save(user);
        log.info("Successfully registered new user with ID: {}", savedUser.getId());
        return UserProfileResponse.fromEntity(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String normalizedEmail = request.getEmail().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!user.isActive()) {
            throw new UnauthorizedException("User account is inactive or disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 1. Generate JWT Access Token
        String accessToken = jwtTokenProvider.generateAccessToken(user);

        // 2. Generate and store Refresh Token Session
        RefreshTokenService.RefreshTokenResult sessionResult =
                refreshTokenService.createSession(user.getId(), clientIp, userAgent);

        // 3. Set HttpOnly and CSRF cookies
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.createAccessTokenCookie(accessToken).toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.createRefreshTokenCookie(sessionResult.rawToken()).toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.createCsrfCookie(UUID.randomUUID().toString()).toString());

        long expiresIn = jwtTokenProvider.getAccessTokenExpirationMs() / 1000;
        return new AuthResponse(accessToken, expiresIn, UserProfileResponse.fromEntity(user));
    }

    @Transactional
    public AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawRefreshToken = CookieUtils.getCookieValue(httpRequest, CookieUtils.REFRESH_TOKEN_COOKIE_NAME)
                .orElseGet(() -> httpRequest.getHeader("X-Refresh-Token"));

        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            throw new UnauthorizedException("Refresh token is required");
        }

        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        try {
            RefreshTokenService.RotationResult rotationResult =
                    refreshTokenService.rotateSession(rawRefreshToken, clientIp, userAgent);

            User user = userRepository.findByIdAndIsActiveTrue(rotationResult.userId())
                    .orElseThrow(() -> new UnauthorizedException("User not found or inactive"));

            String newAccessToken = jwtTokenProvider.generateAccessToken(user);

            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.createAccessTokenCookie(newAccessToken).toString());
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.createRefreshTokenCookie(rotationResult.newRawToken()).toString());

            long expiresIn = jwtTokenProvider.getAccessTokenExpirationMs() / 1000;
            return new AuthResponse(newAccessToken, expiresIn, UserProfileResponse.fromEntity(user));
        } catch (Exception e) {
            // If refresh fails (e.g., reuse or expiry), clear any stale cookies
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearAccessTokenCookie().toString());
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearRefreshTokenCookie().toString());
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearCsrfCookie().toString());
            throw e;
        }
    }

    @Transactional
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawRefreshToken = CookieUtils.getCookieValue(httpRequest, CookieUtils.REFRESH_TOKEN_COOKIE_NAME)
                .orElseGet(() -> httpRequest.getHeader("X-Refresh-Token"));

        if (rawRefreshToken != null && !rawRefreshToken.trim().isEmpty()) {
            refreshTokenService.revokeSessionByToken(rawRefreshToken);
        }

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearAccessTokenCookie().toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearRefreshTokenCookie().toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieUtils.clearCsrfCookie().toString());
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

