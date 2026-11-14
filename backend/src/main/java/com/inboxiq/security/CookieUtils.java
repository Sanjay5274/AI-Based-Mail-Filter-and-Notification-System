package com.inboxiq.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component
public class CookieUtils {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "accessToken";
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    private final boolean secure;
    private final String sameSite;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;

    public CookieUtils(
            @Value("${inboxiq.security.cookie.secure:false}") boolean secure,
            @Value("${inboxiq.security.cookie.same-site:Lax}") String sameSite,
            @Value("${inboxiq.security.jwt.access-token-expiration-ms:900000}") long accessTokenExpirationMs,
            @Value("${inboxiq.security.jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs
    ) {
        this.secure = secure;
        this.sameSite = sameSite;
        this.accessTokenExpirationSeconds = accessTokenExpirationMs / 1000;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationMs / 1000;
    }

    public ResponseCookie createAccessTokenCookie(String token) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api")
                .maxAge(accessTokenExpirationSeconds)
                .build();
    }

    public ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(refreshTokenExpirationSeconds)
                .build();
    }

    public ResponseCookie createCsrfCookie(String csrfToken) {
        return ResponseCookie.from(CSRF_COOKIE_NAME, csrfToken)
                .httpOnly(false)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(refreshTokenExpirationSeconds)
                .build();
    }

    public ResponseCookie clearAccessTokenCookie() {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api")
                .maxAge(0)
                .build();
    }

    public ResponseCookie clearRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }

    public ResponseCookie clearCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE_NAME, "")
                .httpOnly(false)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
    }

    public static Optional<String> getCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(val -> val != null && !val.trim().isEmpty())
                .findFirst();
    }
}

