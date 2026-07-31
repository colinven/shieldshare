ALTER TABLE secrets
ADD CONSTRAINT check_payload_size
CHECK ( octet_length(payload) <= 1052701 ); -- mirrors app.yaml: app.size-caps.max-blob-bytes