-- ==============================================================================
-- InboxIQ - Migration V3: Auth Sessions Table (Refresh Token Rotation & Tracking)
-- ==============================================================================

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    token_family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_token_id UUID,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash)
);

-- Index for querying sessions by user
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);

-- Index for session family invalidation (token reuse detection)
CREATE INDEX idx_auth_sessions_family ON auth_sessions (token_family_id);

-- Partial index for active unrevoked refresh tokens
CREATE INDEX idx_auth_sessions_active ON auth_sessions (token_hash) WHERE revoked_at IS NULL;

