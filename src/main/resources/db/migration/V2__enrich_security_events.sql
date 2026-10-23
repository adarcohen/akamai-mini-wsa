ALTER TABLE security_events
    ADD COLUMN attack_type VARCHAR(64),
    ADD COLUMN threat_score SMALLINT;

CREATE INDEX events_client_received_idx
    ON security_events (client_ip, received_at, event_id);

-- Preserve existing v0.1 data. Its schema did not record order within equal arrival timestamps,
-- so legacy ties use event_id order. New batches are scored in input-array order by the service.
UPDATE security_events current_event
SET attack_type = CASE category
        WHEN 'INJECTION' THEN 'SQL/Command Injection'
        WHEN 'XSS' THEN 'Cross-Site Scripting'
        WHEN 'PROTOCOL_VIOLATION' THEN 'Protocol Anomaly'
        WHEN 'DATA_LEAKAGE' THEN 'Data Exfiltration'
        WHEN 'BOT' THEN 'Bot Activity'
        WHEN 'DOS' THEN 'Denial of Service'
        WHEN 'RATE_LIMIT' THEN 'Rate Limiting'
    END,
    threat_score = LEAST(100,
        CASE payload #>> '{rule,severity}'
            WHEN 'CRITICAL' THEN 40 WHEN 'HIGH' THEN 30
            WHEN 'MEDIUM' THEN 20 WHEN 'LOW' THEN 10
        END
        + CASE action WHEN 'DENY' THEN 20 WHEN 'ALERT' THEN 10 ELSE 0 END
        + CASE WHEN position('/admin' IN path) > 0 OR position('/login' IN path) > 0 THEN 15 ELSE 0 END
        + CASE WHEN (
            SELECT count(*) FROM (
                SELECT 1 FROM security_events previous
                WHERE previous.client_ip = current_event.client_ip
                  AND previous.received_at >= current_event.received_at - INTERVAL '10 minutes'
                  AND (previous.received_at, previous.event_id)
                      < (current_event.received_at, current_event.event_id)
                LIMIT 5
            ) recent
        ) >= 5 THEN 15 ELSE 0 END);

ALTER TABLE security_events
    ALTER COLUMN attack_type SET NOT NULL,
    ALTER COLUMN threat_score SET NOT NULL,
    ADD CONSTRAINT events_threat_score_range CHECK (threat_score BETWEEN 0 AND 100);
