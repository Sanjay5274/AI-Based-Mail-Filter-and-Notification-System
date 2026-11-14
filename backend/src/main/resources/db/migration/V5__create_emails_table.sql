-- ==============================================================================
-- InboxIQ - Migration V5: Emails Table (Canonical Synced Messages)
-- ==============================================================================

CREATE TABLE emails (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connected_account_id UUID NOT NULL,
    user_id UUID NOT NULL,
    gmail_message_id VARCHAR(255) NOT NULL,
    gmail_thread_id VARCHAR(255),
    subject TEXT,
    sender_name VARCHAR(500),
    sender_email VARCHAR(500) NOT NULL,
    recipient_emails TEXT[] NOT NULL DEFAULT '{}',
    received_at TIMESTAMPTZ NOT NULL,
    body_text TEXT,
    body_html TEXT,
    snippet VARCHAR(500),
    label_ids TEXT[] NOT NULL DEFAULT '{}',
    has_attachments BOOLEAN NOT NULL DEFAULT false,
    is_read BOOLEAN NOT NULL DEFAULT false,
    raw_size_estimate INTEGER,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    body_retention_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_emails_account FOREIGN KEY (connected_account_id) REFERENCES connected_accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_emails_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_emails_account_message UNIQUE (connected_account_id, gmail_message_id)
);

-- Index for retrieving user's inbox sorted by received date
CREATE INDEX idx_emails_user_received ON emails (user_id, received_at DESC);

-- Index for message deduplication lookup
CREATE INDEX idx_emails_account_message ON emails (connected_account_id, gmail_message_id);

-- Partial index for automated data retention and body cleanup daemon
CREATE INDEX idx_emails_retention ON emails (body_retention_until) WHERE body_retention_until IS NOT NULL;

