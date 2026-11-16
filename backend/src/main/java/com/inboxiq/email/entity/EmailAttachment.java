package com.inboxiq.email.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_attachments")
public class EmailAttachment implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Transient
    private boolean isNewEntity = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Column(name = "attachment_id", nullable = false, length = 255)
    private String attachmentId;

    @Column(name = "filename", length = 500)
    private String filename;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    @Column(name = "is_inline", nullable = false)
    private boolean isInline = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EmailAttachment() {
        this.id = UUID.randomUUID();
    }

    public EmailAttachment(
            UUID id,
            Email email,
            String attachmentId,
            String filename,
            String mimeType,
            Integer sizeBytes
    ) {
        this(id, email, attachmentId, filename, mimeType, sizeBytes, false);
    }

    public EmailAttachment(
            UUID id,
            Email email,
            String attachmentId,
            String filename,
            String mimeType,
            Integer sizeBytes,
            boolean isInline
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.email = email;
        this.attachmentId = attachmentId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.isInline = isInline;
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
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public String getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(String attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Integer getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Integer sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public boolean isInline() {
        return isInline;
    }

    public void setInline(boolean inline) {
        this.isInline = inline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
