package com.inboxiq.account.repository;

import com.inboxiq.account.entity.ConnectedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {

    List<ConnectedAccount> findAllByUserIdAndIsActiveTrue(UUID userId);

    Optional<ConnectedAccount> findByIdAndUserId(UUID id, UUID userId);

    Optional<ConnectedAccount> findByProviderAndProviderAccountId(String provider, String providerAccountId);

    Optional<ConnectedAccount> findByUserIdAndProviderAndProviderAccountId(UUID userId, String provider, String providerAccountId);

    boolean existsByProviderAndProviderAccountId(String provider, String providerAccountId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        UPDATE ConnectedAccount a
        SET a.syncStatus = :newStatus, a.updatedAt = CURRENT_TIMESTAMP
        WHERE a.id = :id AND a.userId = :userId AND a.syncStatus = :expectedStatus AND a.isActive = true
    """)
    int updateSyncStatusAtomically(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("expectedStatus") String expectedStatus,
            @org.springframework.data.repository.query.Param("newStatus") String newStatus
    );

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM ConnectedAccount a WHERE a.id = :id AND a.userId = :userId")
    Optional<ConnectedAccount> findByIdAndUserIdForUpdate(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId
    );
}

