CREATE INDEX events_config_time_idx ON security_events (config_id, event_time);
CREATE INDEX events_time_idx ON security_events (event_time);
