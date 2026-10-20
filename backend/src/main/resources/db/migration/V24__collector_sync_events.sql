-- Purpose-limited collector reports (#85); no raw observations/location telemetry.
CREATE TABLE collector_sync_events (
    event_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    node_id UUID NOT NULL REFERENCES nodes(node_id) ON DELETE CASCADE,
    incarnation UUID NOT NULL,
    occurred_at BIGINT NOT NULL,
    time_quality VARCHAR(20) NOT NULL CHECK (time_quality IN ('PHONE_ESTIMATE', 'OFFLINE_ESTIMATE', 'UNKNOWN')),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE,
    window_end TIMESTAMP WITH TIME ZONE,
    feeding_site_id UUID REFERENCES feeding_sites(id) ON DELETE SET NULL,
    resolution VARCHAR(24) NOT NULL CHECK (resolution IN ('RESOLVED_ESTIMATE', 'UNRESOLVED', 'AMBIGUOUS'))
);
CREATE INDEX idx_sync_events_site_user ON collector_sync_events(organization_id, feeding_site_id, owner_user_id, window_start);
CREATE INDEX idx_sync_events_retention ON collector_sync_events(received_at);
