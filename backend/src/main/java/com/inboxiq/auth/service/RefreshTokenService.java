package com.inboxiq.auth.service;

import com.inboxiq.auth.entity.AuthSession;
import com.inboxiq.auth.repository.AuthSessionRepository;
import com.inboxiq.common.exception.TokenReuseException;
import com.inboxiq.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Transactional
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthSessionRepository authSessionRepository;
    private final long refreshTokenExpirationMs;

    public RefreshTokenService(
            AuthSessionRepository authSessionRepository,
            @Value("${inboxiq.security.jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs
    ) {
        this.authSessionRepository = authSessionRepository;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    public record RefreshTokenResult(String rawToken, AuthSession session) {}

    public record RotationResult(String newRawToken, UUID userId, UUID tokenFamilyId) {}

    public RefreshTokenResult createSession(UUID userId, String ipAddress, String userAgent) {
        String rawToken = generateSecureOpaqueToken();
        String tokenHash = hashToken(rawToken);
        UUID tokenFamilyId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusMillis(refreshTokenExpirationMs);

        AuthSession session = new AuthSession(
                userId,
                tokenHash,
                tokenFamilyId,
                expiresAt,
                ipAddress,
                userAgent
        );

        session = authSessionRepository.save(session);
        return new RefreshTokenResult(rawToken, session);
    }

    public RotationResult rotateSession(String rawRefreshToken, String ipAddress, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            throw new UnauthorizedException("Refresh token is required");
        }

        String presentedHash = hashToken(rawRefreshToken.trim());
        AuthSession session = authSessionRepository.findByTokenHashWithLock(presentedHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        // Reuse detection: if this session is already revoked, an attacker is replaying an old token
        if (session.isRevoked()) {
            log.warn("SECURITY ALERT: Token reuse detected for token_family_id: {}, user_id: {}, IP: {}",
                    session.getTokenFamilyId(), session.getUserId(), ipAddress);
            authSessionRepository.revokeTokenFamily(session.getTokenFamilyId(), Instant.now());
            throw new TokenReuseException("Refresh token reuse detected. All sessions in this token family have been revoked.");
        }

        if (session.isExpired()) {
            throw new UnauthorizedException("Refresh token has expired");
        }

        // Generate new child token in the same family
        String newRawToken = generateSecureOpaqueToken();
        String newTokenHash = hashToken(newRawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(refreshTokenExpirationMs);

        AuthSession newSession = new AuthSession(
                session.getUserId(),
                newTokenHash,
                session.getTokenFamilyId(),
                expiresAt,
                ipAddress,
                userAgent
        );
        newSession = authSessionRepository.save(newSession);

        // Revoke the old token and link to replacement
        session.setRevokedAt(now);
        session.setLastUsedAt(now);
        session.setReplacedByTokenId(newSession.getId());
        authSessionRepository.save(session);

        return new RotationResult(newRawToken, session.getUserId(), session.getTokenFamilyId());
    }

    public void revokeSessionByToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            return;
        }
        String tokenHash = hashToken(rawRefreshToken.trim());
        authSessionRepository.findByTokenHash(tokenHash).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(Instant.now());
                session.setLastUsedAt(Instant.now());
                authSessionRepository.save(session);
            }
        });
    }

    public void revokeAllSessionsForUser(UUID userId) {
        authSessionRepository.revokeAllUserSessions(userId, Instant.now());
    }

    public static String generateSecureOpaqueToken() {
        byte[] randomBytes = new byte[32]; // 256 bits of entropy
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes);
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in environment", e);
        }
    }
}

