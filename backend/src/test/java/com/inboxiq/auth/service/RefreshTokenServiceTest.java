package com.inboxiq.auth.service;

import com.inboxiq.auth.entity.AuthSession;
import com.inboxiq.auth.repository.AuthSessionRepository;
import com.inboxiq.common.exception.TokenReuseException;
import com.inboxiq.common.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private AuthSessionRepository authSessionRepository;

    private RefreshTokenService refreshTokenService;
    private final long refreshTokenExpirationMs = 604800000; // 7 days

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(authSessionRepository, refreshTokenExpirationMs);
    }

    @Test
    @DisplayName("Should generate 64 hex char opaque token and SHA-256 hash")
    void shouldGenerateSecureTokenAndHash() {
        String token = RefreshTokenService.generateSecureOpaqueToken();
        assertThat(token).isNotNull().hasSize(64).matches("^[0-9a-f]{64}$");

        String hash = RefreshTokenService.hashToken(token);
        assertThat(hash).isNotNull().hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(hash).isEqualTo(RefreshTokenService.hashToken(token));
    }

    @Test
    @DisplayName("Should create new auth session with new token family")
    void shouldCreateNewAuthSession() {
        UUID userId = UUID.randomUUID();
        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.RefreshTokenResult result =
                refreshTokenService.createSession(userId, "127.0.0.1", "Mozilla/5.0");

        assertThat(result.rawToken()).isNotBlank();
        assertThat(result.session()).isNotNull();
        assertThat(result.session().getUserId()).isEqualTo(userId);
        assertThat(result.session().getTokenFamilyId()).isNotNull();
        assertThat(result.session().getTokenHash()).isEqualTo(RefreshTokenService.hashToken(result.rawToken()));
        assertThat(result.session().getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("Should rotate session, revoke old token, and preserve token family")
    void shouldRotateSessionPreservingFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String oldRawToken = RefreshTokenService.generateSecureOpaqueToken();
        String oldHash = RefreshTokenService.hashToken(oldRawToken);

        AuthSession existingSession = new AuthSession(
                userId, oldHash, familyId, Instant.now().plusSeconds(3600), "127.0.0.1", "Mozilla/5.0"
        );
        existingSession.setId(UUID.randomUUID());

        when(authSessionRepository.findByTokenHashWithLock(oldHash)).thenReturn(Optional.of(existingSession));
        when(authSessionRepository.save(any(AuthSession.class))).thenAnswer(inv -> {
            AuthSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });

        RefreshTokenService.RotationResult result =
                refreshTokenService.rotateSession(oldRawToken, "127.0.0.1", "Mozilla/5.0");

        assertThat(result.newRawToken()).isNotEqualTo(oldRawToken);
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.tokenFamilyId()).isEqualTo(familyId);
        assertThat(existingSession.getRevokedAt()).isNotNull();
        assertThat(existingSession.getReplacedByTokenId()).isNotNull();
    }

    @Test
    @DisplayName("Should detect reuse of revoked token and revoke entire token family")
    void shouldDetectReuseAndRevokeFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String compromisedToken = RefreshTokenService.generateSecureOpaqueToken();
        String compromisedHash = RefreshTokenService.hashToken(compromisedToken);

        AuthSession revokedSession = new AuthSession(
                userId, compromisedHash, familyId, Instant.now().plusSeconds(3600), "127.0.0.1", "Mozilla/5.0"
        );
        revokedSession.setId(UUID.randomUUID());
        revokedSession.setRevokedAt(Instant.now().minusSeconds(60)); // already revoked

        when(authSessionRepository.findByTokenHashWithLock(compromisedHash)).thenReturn(Optional.of(revokedSession));

        assertThatThrownBy(() -> refreshTokenService.rotateSession(compromisedToken, "192.168.1.100", "AttackerAgent"))
                .isInstanceOf(TokenReuseException.class)
                .hasMessageContaining("reuse detected");

        verify(authSessionRepository).revokeTokenFamily(eq(familyId), any(Instant.class));
    }

    @Test
    @DisplayName("Should throw UnauthorizedException when token is expired")
    void shouldThrowWhenTokenIsExpired() {
        UUID userId = UUID.randomUUID();
        String rawToken = RefreshTokenService.generateSecureOpaqueToken();
        String hash = RefreshTokenService.hashToken(rawToken);

        AuthSession expiredSession = new AuthSession(
                userId, hash, UUID.randomUUID(), Instant.now().minusSeconds(10), "127.0.0.1", "Mozilla/5.0"
        );
        when(authSessionRepository.findByTokenHashWithLock(hash)).thenReturn(Optional.of(expiredSession));

        assertThatThrownBy(() -> refreshTokenService.rotateSession(rawToken, "127.0.0.1", "Mozilla/5.0"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Should revoke session on logout")
    void shouldRevokeSessionOnLogout() {
        String rawToken = RefreshTokenService.generateSecureOpaqueToken();
        String hash = RefreshTokenService.hashToken(rawToken);

        AuthSession session = new AuthSession(
                UUID.randomUUID(), hash, UUID.randomUUID(), Instant.now().plusSeconds(3600), "127.0.0.1", "Mozilla/5.0"
        );
        when(authSessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(session));

        refreshTokenService.revokeSessionByToken(rawToken);

        assertThat(session.getRevokedAt()).isNotNull();
        verify(authSessionRepository).save(session);
    }
}
