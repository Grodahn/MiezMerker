-- #95: organization-local cat profile photos. One photo per org-local cat;
-- the same chip in two organizations keeps two independent photos.
-- Logical identity (organization_id, cat_id) stays separate from the
-- random physical storage key; files are served only through
-- authorization-checked endpoints, never as public static resources.
CREATE TABLE cat_photos (
    cat_id UUID PRIMARY KEY REFERENCES cats (id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    storage_key VARCHAR(64) NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'image/jpeg',
    byte_size INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_cat_photo_size CHECK (byte_size > 0),
    CONSTRAINT chk_cat_photo_dimensions CHECK (width > 0 AND height > 0),
    CONSTRAINT uq_cat_photo_storage UNIQUE (storage_key)
);
-- Tenant-scoped presence lookups for cat lists; the photo read itself is
-- always keyed by (cat_id, organization_id) so no cross-tenant access.
CREATE INDEX idx_cat_photos_org ON cat_photos (organization_id);
