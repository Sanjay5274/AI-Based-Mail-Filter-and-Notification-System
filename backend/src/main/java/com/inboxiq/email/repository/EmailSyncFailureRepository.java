package com.inboxiq.email.repository;

import com.inboxiq.email.entity.EmailSyncFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailSyncFailureRepository extends JpaRepository<EmailSyncFailure, UUID> {

    Optional<EmailSyncFailure> findByJobIdAndGmailMessageId(UUID jobId, String gmailMessageId);

    List<EmailSyncFailure> findAllByJobIdAndIsResolvedFalse(UUID jobId);

    long countByJobIdAndIsResolvedFalse(UUID jobId);

    List<EmailSyncFailure> findAllByConnectedAccountIdAndIsResolvedFalse(UUID connectedAccountId);
}
