CREATE TABLE audit_logs (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(50) CHECK ( event_type IN ('SECRET_CREATED',
                                                  'SECRET_VALIDATED',
                                                  'SECRET_FETCHED_SUCCESS',
                                                  'SECRET_FETCHED_FAILURE',
                                                  'SECRET_DELETED_EXPIRED',
                                                  'SECRET_DELETED_CONSUMED') ) NOT NULL,
    resource_id VARCHAR(24) NOT NULL,
    source_ip VARCHAR(50),
    event_timestamp timestamptz DEFAULT now() NOT NULL
);