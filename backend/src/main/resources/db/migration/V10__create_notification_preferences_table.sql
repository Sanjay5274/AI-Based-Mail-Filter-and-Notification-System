-- ==============================================================================
-- InboxIQ - Migration V10: Notification Preferences Table
-- ==============================================================================

CREATE TABLE notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    sms_enabled BOOLEAN NOT NULL DEFAULT false,
    sms_number VARCHAR(20),
    email_enabled BOOLEAN NOT NULL DEFAULT false,
    notification_email VARCHAR(255),
    global_min_relevance_score INTEGER NOT NULL DEFAULT 60,
    quiet_hours_enabled BOOLEAN NOT NULL DEFAULT false,
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notif_prefs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_notif_prefs_user UNIQUE (user_id),
    CONSTRAINT chk_notif_prefs_score CHECK (global_min_relevance_score BETWEEN 0 AND 100)
);

