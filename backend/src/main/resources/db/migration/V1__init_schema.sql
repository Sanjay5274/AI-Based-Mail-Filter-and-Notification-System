-- ==============================================================================
-- InboxIQ - Initial Migration: Database Extensions & Schema Initialization
-- Version: V1
-- ==============================================================================

-- Enable UUID generation functions
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Schema metadata table for infrastructure validation
CREATE TABLE IF NOT EXISTS system_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_metadata (key, value)
VALUES ('schema_initialized', 'true')
ON CONFLICT (key) DO NOTHING;
