CREATE TABLE security_events (
    event_id VARCHAR(128) PRIMARY KEY,
    event_time TIMESTAMPTZ NOT NULL,
    config_id BIGINT NOT NULL CHECK (config_id > 0),
    category VARCHAR(32) NOT NULL CHECK (category IN
        ('INJECTION', 'XSS', 'PROTOCOL_VIOLATION', 'DATA_LEAKAGE', 'BOT', 'DOS', 'RATE_LIMIT')),
    action VARCHAR(16) NOT NULL CHECK (action IN ('DENY', 'ALERT', 'MONITOR')),
    client_ip INET NOT NULL,
    path VARCHAR(4096) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
