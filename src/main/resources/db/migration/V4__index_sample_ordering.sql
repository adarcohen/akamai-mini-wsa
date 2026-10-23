-- Replace the V3 indexes with versions that also support stable pagination for equal timestamps.
DROP INDEX events_config_time_idx;
CREATE INDEX events_config_time_idx
    ON security_events (config_id, event_time DESC, event_id DESC);

DROP INDEX events_time_idx;
CREATE INDEX events_time_idx
    ON security_events (event_time DESC, event_id DESC);
