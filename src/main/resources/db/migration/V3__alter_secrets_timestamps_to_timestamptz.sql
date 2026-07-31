ALTER TABLE secrets
ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN expires_at TYPE timestamptz USING expires_at AT TIME ZONE 'UTC',
ALTER COLUMN consumed_at TYPE timestamptz USING consumed_at AT TIME ZONE 'UTC';