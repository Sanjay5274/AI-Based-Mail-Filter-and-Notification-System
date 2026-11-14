-- ==============================================================================
-- InboxIQ - Migration V9: User Category Preferences Table
-- ==============================================================================

CREATE TABLE user_category_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    category_id UUID NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    min_relevance_score INTEGER NOT NULL DEFAULT 60,
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    notify_via_sms BOOLEAN NOT NULL DEFAULT true,
    notify_via_email BOOLEAN NOT NULL DEFAULT false,
    preferences_data JSONB DEFAULT '{}',
    keywords_include TEXT[] NOT NULL DEFAULT '{}',
    keywords_exclude TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ucp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ucp_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT uq_ucp_user_category UNIQUE (user_id, category_id),
    CONSTRAINT chk_ucp_score CHECK (min_relevance_score BETWEEN 0 AND 100),
    CONSTRAINT chk_ucp_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- Indexes for preference lookups
CREATE INDEX idx_ucp_user ON user_category_preferences (user_id);
CREATE INDEX idx_ucp_category ON user_category_preferences (category_id);

