package com.inboxiq.email.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "email_sync_failures",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_sync_failures_job_msg", columnNames = {"job_id", "gmail_message_id"})
        }
)
public class EmailSyncFailure implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Transient
    private boolean isNewEntity = true;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "connected_account_id", nullable = false)
    private UUID connectedAccountId;

    @Column(name = "gmail_message_id", nullable = false, length = 255)
    private String gmailMessageId;

    @Column(name = "failure_category", nullable = false, length = 50)
    private String failureCategory;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "is_resolved", nullable = false)
    private boolean isResolved = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EmailSyncFailure() {
        this.id = UUID.randomUUID();
    }

    public EmailSyncFailure(
            UUID id,
            UUID jobId,
            UUID connectedAccountId,
            String gmailMessageId,
            String failureCategory,
            String errorMessage
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.jobId = jobId;
        this.connectedAccountId = connectedAccountId;
        this.gmailMessageId = gmailMessageId;
        this.failureCategory = failureCategory;
        this.errorMessage = errorMessage;
        this.retryCount = 0;
        this.isResolved = false;
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
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public void resolve() {
        this.isResolved = true;
    }

    // Getters and Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getConnectedAccountId() {
        return connectedAccountId;
    }

    public void setConnectedAccountId(UUID connectedAccountId) {
        this.connectedAccountId = connectedAccountId;
    }

    public String getGmailMessageId() {
        return gmailMessageId;
    }

    public void setGmailMessageId(String gmailMessageId) {
        this.gmailMessageId = gmailMessageId;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public boolean isResolved() {
        return isResolved;
    }

    public void setResolved(boolean resolved) {
        isResolved = resolved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
