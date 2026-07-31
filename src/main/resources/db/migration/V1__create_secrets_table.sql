CREATE TABLE secrets (
    secret_id VARCHAR(24) PRIMARY KEY,
    state VARCHAR(20) CHECK ( state IN ('ACTIVE', 'CONSUMED') ) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT now() NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP
);
