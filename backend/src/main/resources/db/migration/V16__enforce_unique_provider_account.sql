-- ==============================================================================
-- InboxIQ - Migration V16: Enforce Global Uniqueness for Connected Provider Accounts
-- Prevents a single external Google account identity (provider, provider_account_id)
-- from being connected to multiple InboxIQ users simultaneously.
-- ==============================================================================

ALTER TABLE connected_accounts
ADD CONSTRAINT uq_connected_accounts_provider_account UNIQUE (provider, provider_account_id);

