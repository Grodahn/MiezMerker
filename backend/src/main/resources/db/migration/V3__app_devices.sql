CREATE TABLE app_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    public_key_x VARCHAR(64) NOT NULL,
    public_key_y VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_app_devices_user ON app_devices (user_id);
