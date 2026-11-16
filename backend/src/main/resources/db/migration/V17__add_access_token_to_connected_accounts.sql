-- ==============================================================================
-- InboxIQ - Migration V17: Add Encrypted Access Token Fields to Connected Accounts
-- Purpose: Store the Google OAuth access token encrypted (AES-256-GCM) alongside
-- the refresh token so the Gmail API client can authenticate without decrypting the
-- refresh token on every request. Access tokens are short-lived (<= 1 hour) and are
-- refreshed automatically when expired. Nullable because existing rows were inserted
-- before this column existed; they will be populated on first token refresh.
-- ==============================================================================

ALTER TABLE connected_accounts
    ADD COLUMN IF NOT EXISTS access_token_ciphertext     TEXT         NULL,
    ADD COLUMN IF NOT EXISTS access_token_key_version    VARCHAR(50)  NULL,
    ADD COLUMN IF NOT EXISTS access_token_nonce_iv       VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS access_token_auth_tag       VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS access_token_expires_at     TIMESTAMPTZ  NULL;

COMMENT ON COLUMN connected_accounts.access_token_ciphertext  IS 'AES-256-GCM encrypted Google OAuth access token (Base64)';
COMMENT ON COLUMN connected_accounts.access_token_key_version IS 'Encryption key version used for access_token_ciphertext';
COMMENT ON COLUMN connected_accounts.access_token_nonce_iv    IS 'Base64-encoded 96-bit GCM nonce for access token';
COMMENT ON COLUMN connected_accounts.access_token_auth_tag    IS 'Base64-encoded 128-bit GCM authentication tag for access token';
COMMENT ON COLUMN connected_accounts.access_token_expires_at  IS 'UTC timestamp when the stored access token expires';
