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
}

