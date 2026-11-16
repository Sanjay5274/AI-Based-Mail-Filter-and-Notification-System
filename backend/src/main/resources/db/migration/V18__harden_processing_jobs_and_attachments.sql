-- ==============================================================================
-- InboxIQ - Migration V18: Hardening Processing Jobs, Attachments, and Sync Failures
-- ==============================================================================

-- 1. Add is_inline flag to email_attachments
ALTER TABLE email_attachments
    ADD COLUMN is_inline BOOLEAN NOT NULL DEFAULT false;

-- 2. Prevent concurrent active jobs for the same target entity (account)
CREATE UNIQUE INDEX uq_pj_active_target
    ON processing_jobs (target_type, target_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT');

-- 3. Add pagination checkpoint and execution progress metrics to processing_jobs
ALTER TABLE processing_jobs
    ADD COLUMN pagination_token VARCHAR(500),
    ADD COLUMN lookback_days INTEGER,
    ADD COLUMN total_found INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN processed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN duplicate_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN initial_history_id VARCHAR(100);

-- 4. Durable tracking of failed messages during historical/incremental sync
CREATE TABLE email_sync_failures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES processing_jobs(id) ON DELETE CASCADE,
    connected_account_id UUID NOT NULL REFERENCES connected_accounts(id) ON DELETE CASCADE,
    gmail_message_id VARCHAR(255) NOT NULL,
    failure_category VARCHAR(50) NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    is_resolved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sync_failures_job_msg UNIQUE (job_id, gmail_message_id),
    CONSTRAINT chk_sync_failure_cat CHECK (failure_category IN ('TRANSIENT', 'PERMANENT', 'PARSE_ERROR'))
);

CREATE INDEX idx_sync_failures_job_unresolved
    ON email_sync_failures (job_id, is_resolved)
    WHERE is_resolved = false;

CREATE INDEX idx_sync_failures_account
    ON email_sync_failures (connected_account_id);
