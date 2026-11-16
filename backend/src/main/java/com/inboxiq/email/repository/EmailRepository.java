package com.inboxiq.email.repository;

import com.inboxiq.email.entity.Email;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailRepository extends JpaRepository<Email, UUID> {

    boolean existsByConnectedAccountIdAndGmailMessageId(UUID connectedAccountId, String gmailMessageId);

    Optional<Email> findByConnectedAccountIdAndGmailMessageId(UUID connectedAccountId, String gmailMessageId);

    List<Email> findAllByConnectedAccountId(UUID connectedAccountId);

    Page<Email> findAllByUserId(UUID userId, Pageable pageable);

    long countByConnectedAccountId(UUID connectedAccountId);
}
