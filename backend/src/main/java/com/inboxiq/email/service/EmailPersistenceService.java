package com.inboxiq.email.service;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.email.entity.Email;
import com.inboxiq.email.entity.EmailAttachment;
import com.inboxiq.email.entity.EmailSyncFailure;
import com.inboxiq.email.parser.ParsedAttachment;
import com.inboxiq.email.parser.ParsedEmail;
import com.inboxiq.email.repository.EmailAttachmentRepository;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.repository.EmailSyncFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated persistence service that executes isolated, short transactions
 * for individual email saves and failure tracking.
 */
@Service
public class EmailPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(EmailPersistenceService.class);

    private final EmailRepository emailRepository;
    private final EmailAttachmentRepository emailAttachmentRepository;
    private final EmailSyncFailureRepository emailSyncFailureRepository;

    public EmailPersistenceService(
            EmailRepository emailRepository,
            EmailAttachmentRepository emailAttachmentRepository,
            EmailSyncFailureRepository emailSyncFailureRepository
    ) {
        this.emailRepository = emailRepository;
        this.emailAttachmentRepository = emailAttachmentRepository;
        this.emailSyncFailureRepository = emailSyncFailureRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveEmailWithAttachments(ConnectedAccount account, ParsedEmail parsed) {
        if (emailRepository.existsByConnectedAccountIdAndGmailMessageId(account.getId(), parsed.gmailMessageId())) {
            return false;
        }

        Email email = new Email(
                UUID.randomUUID(),
                account.getId(),
                account.getUserId(),
                parsed.gmailMessageId(),
                parsed.gmailThreadId(),
                parsed.subject(),
                parsed.senderName(),
                parsed.senderEmail(),
                parsed.recipientEmails().toArray(new String[0]),
                parsed.receivedAt(),
                parsed.bodyText(),
                parsed.bodyHtml(),
                parsed.snippet(),
                parsed.labelIds().toArray(new String[0]),
                parsed.hasAttachments(),
                parsed.isRead(),
                parsed.rawSizeEstimate()
        );

        if (parsed.hasAttachments() && parsed.attachments() != null) {
            for (ParsedAttachment att : parsed.attachments()) {
                EmailAttachment attachment = new EmailAttachment(
                        UUID.randomUUID(),
                        email,
                        att.attachmentId(),
                        att.filename(),
                        att.mimeType(),
                        att.sizeBytes(),
                        att.isInline()
                );
                email.addAttachment(attachment);
            }
        }

        try {
            emailRepository.save(email);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent insertion detected for message {}, treating as duplicate", parsed.gmailMessageId());
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSyncFailure(UUID jobId, UUID accountId, String gmailMessageId, String category, String sanitizedError) {
        Optional<EmailSyncFailure> existing = emailSyncFailureRepository.findByJobIdAndGmailMessageId(jobId, gmailMessageId);
        if (existing.isPresent()) {
            EmailSyncFailure failure = existing.get();
            failure.incrementRetryCount();
            failure.setErrorMessage(sanitizedError);
            failure.setFailureCategory(category);
            failure.setResolved(false);
            emailSyncFailureRepository.saveAndFlush(failure);
        } else {
            EmailSyncFailure failure = new EmailSyncFailure(
                    UUID.randomUUID(),
                    jobId,
                    accountId,
                    gmailMessageId,
                    category,
                    sanitizedError
            );
            emailSyncFailureRepository.saveAndFlush(failure);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSyncFailureResolved(UUID jobId, String gmailMessageId) {
        emailSyncFailureRepository.findByJobIdAndGmailMessageId(jobId, gmailMessageId).ifPresent(failure -> {
            failure.resolve();
            emailSyncFailureRepository.saveAndFlush(failure);
        });
    }
}
