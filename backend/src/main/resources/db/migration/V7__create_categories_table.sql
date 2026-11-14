-- ==============================================================================
-- InboxIQ - Migration V7: Categories Table (System & Custom Categories)
-- ==============================================================================

CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    is_system BOOLEAN NOT NULL DEFAULT false,
    owner_user_id UUID,
    icon VARCHAR(100),
    color VARCHAR(50),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_categories_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_categories_ownership CHECK (
        (is_system = true AND owner_user_id IS NULL) OR
        (is_system = false AND owner_user_id IS NOT NULL)
    )
);

-- System categories: slug must be globally unique
CREATE UNIQUE INDEX uq_categories_system_slug ON categories (slug) WHERE is_system = true;

-- Custom categories: slug must be unique per owner user
CREATE UNIQUE INDEX uq_categories_custom_user_slug ON categories (owner_user_id, slug) WHERE is_system = false;

-- Index for listing system categories
CREATE INDEX idx_categories_system ON categories (is_system) WHERE is_system = true;

-- Index for retrieving user custom categories
CREATE INDEX idx_categories_user ON categories (owner_user_id) WHERE owner_user_id IS NOT NULL;

