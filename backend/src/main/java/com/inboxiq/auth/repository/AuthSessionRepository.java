package com.inboxiq.auth.repository;

import com.inboxiq.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSession s WHERE s.tokenHash = :tokenHash")
    Optional<AuthSession> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    List<AuthSession> findAllByTokenFamilyId(UUID tokenFamilyId);

    List<AuthSession> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :revokedAt WHERE s.tokenFamilyId = :familyId AND s.revokedAt IS NULL")
    int revokeTokenFamily(@Param("familyId") UUID familyId, @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :revokedAt WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllUserSessions(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}

