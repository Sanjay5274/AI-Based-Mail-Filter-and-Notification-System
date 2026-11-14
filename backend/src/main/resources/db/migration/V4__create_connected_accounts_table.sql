-- ==============================================================================
-- InboxIQ - Migration V4: Connected Accounts Table (Google OAuth Tokens & Sync State)
-- ==============================================================================

CREATE TABLE connected_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL DEFAULT 'GOOGLE',
    provider_account_id VARCHAR(255) NOT NULL,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    refresh_token_ciphertext TEXT NOT NULL,
    encryption_key_version VARCHAR(50) NOT NULL DEFAULT 'v1',
    nonce_iv VARCHAR(64) NOT NULL,
    auth_tag VARCHAR(64) NOT NULL,
    scopes TEXT[] NOT NULL DEFAULT '{}',
    sync_status VARCHAR(50) NOT NULL DEFAULT 'IDLE',
    last_sync_at TIMESTAMPTZ,
    last_history_id VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_connected_accounts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_connected_accounts_user_provider_account UNIQUE (user_id, provider_account_id),
    CONSTRAINT chk_connected_accounts_sync_status CHECK (sync_status IN ('IDLE', 'SYNCING', 'FAILED', 'DISCONNECTING'))
);

-- Index for retrieving connected accounts by user
CREATE INDEX idx_connected_accounts_user ON connected_accounts (user_id);

-- Partial index for active accounts scheduled for sync
CREATE INDEX idx_connected_accounts_active ON connected_accounts (is_active) WHERE is_active = true;

