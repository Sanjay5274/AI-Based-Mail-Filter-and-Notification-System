-- ==============================================================================
-- InboxIQ - Migration V6: Email Attachments Metadata Table
-- ==============================================================================

CREATE TABLE email_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_id UUID NOT NULL,
    attachment_id VARCHAR(255) NOT NULL,
    filename VARCHAR(500),
    mime_type VARCHAR(255),
    size_bytes INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_attachments_email FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
);

-- Index for retrieving attachments for an email
CREATE INDEX idx_email_attachments_email ON email_attachments (email_id);

