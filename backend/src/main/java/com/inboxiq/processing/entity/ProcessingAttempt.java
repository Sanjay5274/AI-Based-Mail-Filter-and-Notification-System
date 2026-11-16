package com.inboxiq.processing.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "processing_attempts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pa_job_attempt", columnNames = {"job_id", "attempt_number"})
        }
)
public class ProcessingAttempt implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Transient
    private boolean isNewEntity = true;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_sanitized", columnDefinition = "TEXT")
    private String errorSanitized;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ProcessingAttempt() {
        this.id = UUID.randomUUID();
    }

    public ProcessingAttempt(
            UUID id,
            UUID jobId,
            int attemptNumber,
            Instant startedAt,
            String status
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.status = status;
        this.createdAt = Instant.now();
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
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    public void complete(String finalStatus, Integer durationMs, String errorMessage) {
        this.status = finalStatus;
        this.completedAt = Instant.now();
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.errorSanitized = errorMessage != null ? errorMessage.replaceAll("Bearer\\s+[A-Za-z0-9_.-]+", "Bearer [REDACTED]") : null;
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

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorSanitized() {
        return errorSanitized;
    }

    public void setErrorSanitized(String errorSanitized) {
        this.errorSanitized = errorSanitized;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
