-- ==============================================================================
-- InboxIQ - Migration V14: Notifications & Notification Deliveries Tables
-- ==============================================================================

-- Consolidated single notification event per email per user
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    email_id UUID NOT NULL,
    notification_type VARCHAR(50) NOT NULL DEFAULT 'EMAIL_MATCH',
    matched_category_ids UUID[] NOT NULL DEFAULT '{}',
    max_relevance_score INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    message TEXT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_notif_email FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
    CONSTRAINT uq_notif_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_notif_score CHECK (max_relevance_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_notif_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notif_email ON notifications (email_id);

-- Individual channel delivery tracking (SMS, Email, WhatsApp, Push)
CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL,
    channel VARCHAR(50) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_message_id VARCHAR(500),
    recipient_address VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255) NOT NULL,
    last_attempted_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    failure_code VARCHAR(100),
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_nd_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT uq_nd_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_nd_status CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_nd_notification ON notification_deliveries (notification_id);
CREATE INDEX idx_nd_status ON notification_deliveries (status) WHERE status = 'PENDING';
CREATE INDEX idx_nd_provider_msg ON notification_deliveries (provider_message_id);

