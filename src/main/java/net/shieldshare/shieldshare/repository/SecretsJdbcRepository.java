package net.shieldshare.shieldshare.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SecretsJdbcRepository {

    private final JdbcClient jdbc;

    /**
     * Insert a row into the secrets table and gracefully handle ID collisions. If a collision occurs, this method will
     * return an empty Optional. The caller is responsible for handling this case and retrying as necessary.
     * @param id 128-bit Base64 encoded String (22 chars)
     * @param payload AES encrypted binary blob
     * @param ttlSeconds int representing number of seconds until record should expire
     * @return an Instant value representing the expiration of the newly inserted row, or empty Optional on ID collision.
     */
    public Optional<Instant> insert(String id, byte[] payload, int ttlSeconds) {
        return jdbc.sql("""
                INSERT INTO secrets (secret_id, state, payload, created_at, expires_at)
                VALUES (:id, 'ACTIVE', :payload, now(), now() + make_interval(secs => :ttl))
                ON CONFLICT (secret_id) DO NOTHING
                RETURNING expires_at;
                """)
                .param("id", id)
                .param("payload", payload)
                .param("ttl", ttlSeconds)
                .query(Instant.class)
                .optional();
    }

    /**
     * Peek to see if an ACTIVE, NON-EXPIRED secret exists in the database by ID. Use this method solely to prove
     * existence, without consuming the secret itself or returning the payload.
     * @param secretId the id to search for
     * @return mirrors back the ID if a row is found, or an empty Optional if no valid row is found
     */
    public Optional<String> validate(String secretId) {
        return jdbc.sql("""
                SELECT secret_id FROM secrets
                WHERE secret_id = :id AND state = 'ACTIVE' AND expires_at > now();
                """)
                .param("id", secretId)
                .query(String.class)
                .optional();
    }
}
