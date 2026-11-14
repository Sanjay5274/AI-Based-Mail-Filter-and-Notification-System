-- ==============================================================================
-- InboxIQ - Migration V12: Email Classification Categories Table
-- ==============================================================================

CREATE TABLE email_classification_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    classification_id UUID NOT NULL,
    category_id UUID NOT NULL,
    confidence_score DECIMAL(5,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ecc_classification FOREIGN KEY (classification_id) REFERENCES email_classifications(id) ON DELETE CASCADE,
    CONSTRAINT fk_ecc_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT,
    CONSTRAINT uq_ecc_classification_category UNIQUE (classification_id, category_id),
    CONSTRAINT chk_ecc_confidence CHECK (confidence_score BETWEEN 0.0000 AND 1.0000)
);

-- Indexes for classification category lookups
CREATE INDEX idx_ecc_classification ON email_classification_categories (classification_id);
CREATE INDEX idx_ecc_category ON email_classification_categories (category_id);

