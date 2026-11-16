package com.inboxiq.email.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "emails",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_emails_account_message", columnNames = {"connected_account_id", "gmail_message_id"})
        }
)
public class Email implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Transient
    private boolean isNewEntity = true;

    @Column(name = "connected_account_id", nullable = false)
    private UUID connectedAccountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "gmail_message_id", nullable = false, length = 255)
    private String gmailMessageId;

    @Column(name = "gmail_thread_id", length = 255)
    private String gmailThreadId;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "sender_name", length = 500)
    private String senderName;

    @Column(name = "sender_email", nullable = false, length = 500)
    private String senderEmail;

    @Column(name = "recipient_emails", columnDefinition = "text[]", nullable = false)
    private String[] recipientEmails = new String[0];

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "snippet", length = 500)
    private String snippet;

    @Column(name = "label_ids", columnDefinition = "text[]", nullable = false)
    private String[] labelIds = new String[0];

    @Column(name = "has_attachments", nullable = false)
    private boolean hasAttachments = false;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "raw_size_estimate")
    private Integer rawSizeEstimate;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "body_retention_until")
    private Instant bodyRetentionUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "email", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EmailAttachment> attachments = new ArrayList<>();

    public Email() {
        this.id = UUID.randomUUID();
    }

    public Email(
            UUID id,
            UUID connectedAccountId,
            UUID userId,
            String gmailMessageId,
            String gmailThreadId,
            String subject,
            String senderName,
            String senderEmail,
            String[] recipientEmails,
            Instant receivedAt,
            String bodyText,
            String bodyHtml,
            String snippet,
            String[] labelIds,
            boolean hasAttachments,
            boolean isRead,
            Integer rawSizeEstimate
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.connectedAccountId = connectedAccountId;
        this.userId = userId;
        this.gmailMessageId = gmailMessageId;
        this.gmailThreadId = gmailThreadId;
        this.subject = subject;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
        this.recipientEmails = recipientEmails != null ? recipientEmails : new String[0];
        this.receivedAt = receivedAt != null ? receivedAt : Instant.now();
        this.bodyText = bodyText;
        this.bodyHtml = bodyHtml;
        this.snippet = snippet;
        this.labelIds = labelIds != null ? labelIds : new String[0];
        this.hasAttachments = hasAttachments;
        this.isRead = isRead;
        this.rawSizeEstimate = rawSizeEstimate;
        this.syncedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNewEntity = false;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.syncedAt == null) {
            this.syncedAt = now;
        }
    }

    public void addAttachment(EmailAttachment attachment) {
        attachments.add(attachment);
        attachment.setEmail(this);
    }

    // Getters and Setters

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getConnectedAccountId() {
        return connectedAccountId;
    }

    public void setConnectedAccountId(UUID connectedAccountId) {
        this.connectedAccountId = connectedAccountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getGmailMessageId() {
        return gmailMessageId;
    }

    public void setGmailMessageId(String gmailMessageId) {
        this.gmailMessageId = gmailMessageId;
    }

    public String getGmailThreadId() {
        return gmailThreadId;
    }

    public void setGmailThreadId(String gmailThreadId) {
        this.gmailThreadId = gmailThreadId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String[] getRecipientEmails() {
        return recipientEmails;
    }

    public void setRecipientEmails(String[] recipientEmails) {
        this.recipientEmails = recipientEmails;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public void setBodyHtml(String bodyHtml) {
        this.bodyHtml = bodyHtml;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String[] getLabelIds() {
        return labelIds;
    }

    public void setLabelIds(String[] labelIds) {
        this.labelIds = labelIds;
    }

    public boolean isHasAttachments() {
        return hasAttachments;
    }

    public void setHasAttachments(boolean hasAttachments) {
        this.hasAttachments = hasAttachments;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public Integer getRawSizeEstimate() {
        return rawSizeEstimate;
    }

    public void setRawSizeEstimate(Integer rawSizeEstimate) {
        this.rawSizeEstimate = rawSizeEstimate;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Instant syncedAt) {
        this.syncedAt = syncedAt;
    }

    public Instant getBodyRetentionUntil() {
        return bodyRetentionUntil;
    }

    public void setBodyRetentionUntil(Instant bodyRetentionUntil) {
        this.bodyRetentionUntil = bodyRetentionUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<EmailAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<EmailAttachment> attachments) {
        this.attachments = attachments;
    }
}
