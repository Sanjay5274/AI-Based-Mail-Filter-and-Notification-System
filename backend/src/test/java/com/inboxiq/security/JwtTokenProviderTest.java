package com.inboxiq.security;

import com.inboxiq.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private final String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(secret, 900000, "inboxiq-api");
    }

    @Test
    @DisplayName("Should generate and validate valid JWT token")
    void shouldGenerateAndValidateToken() {
        User user = new User("user@example.com", "hash", "Test User", "+1234567890");
        user.setId(UUID.randomUUID());

        String token = jwtTokenProvider.generateAccessToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(user.getId());
        assertThat(jwtTokenProvider.getEmailFromToken(token)).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("Should reject tampered or malformed token")
    void shouldRejectTamperedToken() {
        User user = new User("user@example.com", "hash", "Test User", null);
        user.setId(UUID.randomUUID());

        String token = jwtTokenProvider.generateAccessToken(user);
        String tamperedToken = token + "xyz";

        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
        assertThat(jwtTokenProvider.validateToken("invalid.token.here")).isFalse();
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
        assertThat(jwtTokenProvider.validateToken(null)).isFalse();
    }

    @Test
    @DisplayName("Should reject expired token")
    void shouldRejectExpiredToken() {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(secret, -1000, "inboxiq-api");
        User user = new User("user@example.com", "hash", "Test User", null);
        user.setId(UUID.randomUUID());

        String expiredToken = shortLivedProvider.generateAccessToken(user);

        assertThat(shortLivedProvider.validateToken(expiredToken)).isFalse();
    }
}

