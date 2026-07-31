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
    Optional<Instant> insert(String id, byte[] payload, int ttlSeconds) {
        return jdbc.sql("""
                INSERT INTO secrets (id, state, payload, created_at, expires_at)
                VALUES (:id, 'ACTIVE', :payload, now(), make_interval(secs => :ttl)
                ON CONFLICT (id) DO NOTHING
                RETURNING expires_at
                """)
                .param("id", id)
                .param("payload", payload)
                .param("ttl", ttlSeconds)
                .query(Instant.class)
                .optional();
    }
}
