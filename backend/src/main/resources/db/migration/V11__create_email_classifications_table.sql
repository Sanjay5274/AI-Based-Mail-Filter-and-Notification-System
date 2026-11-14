-- ==============================================================================
-- InboxIQ - Migration V11: Email Classifications Table (Versioned AI Inference)
-- ==============================================================================

CREATE TABLE email_classifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_id UUID NOT NULL,
    model_provider VARCHAR(50) NOT NULL DEFAULT 'GEMINI',
    model_name VARCHAR(100) NOT NULL DEFAULT 'gemini-1.5-flash',
    model_version VARCHAR(50) NOT NULL DEFAULT '001',
    prompt_version VARCHAR(50) NOT NULL DEFAULT 'v1.0',
    classification_status VARCHAR(50) NOT NULL DEFAULT 'SUCCESS',
    ai_summary TEXT,
    priority VARCHAR(20),
    is_spam BOOLEAN NOT NULL DEFAULT false,
    extracted_data JSONB DEFAULT '{}',
    is_current BOOLEAN NOT NULL DEFAULT true,
    processing_duration_ms INTEGER,
    classified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_email_classifications_email FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
    CONSTRAINT chk_class_status CHECK (classification_status IN ('SUCCESS', 'VALIDATION_FAILED', 'ERROR')),
    CONSTRAINT chk_class_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
);

-- Version enforcement: exactly one current active classification per email
CREATE UNIQUE INDEX uq_email_classifications_current ON email_classifications (email_id) WHERE is_current = true;

-- Index for retrieving all classification runs for an email
CREATE INDEX idx_email_classifications_email ON email_classifications (email_id);

