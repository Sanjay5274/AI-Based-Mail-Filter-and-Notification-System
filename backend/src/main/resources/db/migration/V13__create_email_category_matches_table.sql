-- ==============================================================================
-- InboxIQ - Migration V13: Email Category Matches Table (User-Specific Matching)
-- ==============================================================================

CREATE TABLE email_category_matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    email_id UUID NOT NULL,
    category_id UUID NOT NULL,
    relevance_score INTEGER NOT NULL,
    is_matched BOOLEAN NOT NULL DEFAULT false,
    match_explanation TEXT,
    matched_preferences JSONB DEFAULT '{}',
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ecm_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ecm_email FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
    CONSTRAINT fk_ecm_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
    CONSTRAINT uq_ecm_user_email_category UNIQUE (user_id, email_id, category_id),
    CONSTRAINT chk_ecm_relevance CHECK (relevance_score BETWEEN 0 AND 100)
);

-- Index for retrieving user matches sorted by processing timestamp
CREATE INDEX idx_ecm_user_matched ON email_category_matches (user_id, is_matched, processed_at DESC);

-- Index for looking up all match evaluations for an email
CREATE INDEX idx_ecm_email ON email_category_matches (email_id);

