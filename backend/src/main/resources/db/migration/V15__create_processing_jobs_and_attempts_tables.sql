-- ==============================================================================
-- InboxIQ - Migration V15: Processing Jobs & Processing Attempts Tables
-- Durable PostgreSQL-backed job queue with lease-based locking and recovery
-- ==============================================================================

CREATE TABLE processing_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    priority INTEGER NOT NULL DEFAULT 5,
    idempotency_key VARCHAR(500) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(100),
    locked_until TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pj_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_pj_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'DEAD'))
);

-- Critical index for high-concurrency lease claim query using FOR UPDATE SKIP LOCKED
CREATE INDEX idx_pj_dispatcher ON processing_jobs (status, next_retry_at, priority ASC, created_at ASC)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

-- Index for scheduled recovery of crashed worker leases
CREATE INDEX idx_pj_recovery ON processing_jobs (status, locked_until)
    WHERE status = 'PROCESSING';

-- Index for target entity lookups
CREATE INDEX idx_pj_target ON processing_jobs (target_type, target_id);

-- Immutable audit log for all execution attempts
CREATE TABLE processing_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    error_sanitized TEXT,
    duration_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pa_job FOREIGN KEY (job_id) REFERENCES processing_jobs(id) ON DELETE CASCADE,
    CONSTRAINT uq_pa_job_attempt UNIQUE (job_id, attempt_number)
);

CREATE INDEX idx_pa_job ON processing_attempts (job_id);

