CREATE TABLE unsuccessful_access_attempts (
    event_id UUID PRIMARY KEY,
    resource_id VARCHAR(24) NOT NULL,
    source_ip VARCHAR(50),
    event_timestamp timestamptz DEFAULT now() NOT NULL
);