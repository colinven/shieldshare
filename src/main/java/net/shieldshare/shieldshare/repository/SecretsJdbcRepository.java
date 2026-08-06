package net.shieldshare.shieldshare.repository;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.model.Secret;
import net.shieldshare.shieldshare.model.SecretState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SecretsJdbcRepository {

    private final JdbcClient jdbc;
    private final AppProperties appProperties;

    /**
     * Insert a row into the secrets table and gracefully handle ID collisions. If a collision occurs, this method will
     * return an empty Optional. The caller is responsible for handling this case and retrying as necessary.
     * @param id 128-bit Base64 encoded String (22 chars)
     * @param payload AES encrypted binary blob
     * @param ttlSeconds int representing number of seconds until record should expire
     * @return a Secret record mirroring the newly inserted row, or empty Optional on ID collision.
     */
    public Optional<Secret> insert(String id, byte[] payload, int ttlSeconds) {
        return jdbc.sql("""
                INSERT INTO secrets (secret_id, state, payload, created_at, expires_at)
                VALUES (:id, :state, :payload, now(), now() + make_interval(secs => :ttl))
                ON CONFLICT (secret_id) DO NOTHING
                RETURNING *;
                """)
                .param("id", id)
                .param("state", SecretState.ACTIVE.name())
                .param("payload", payload)
                .param("ttl", ttlSeconds)
                .query((rs, rowNum) -> new Secret(
                        rs.getString("secret_id"),
                        SecretState.valueOf(rs.getString("state")),
                        null,
                        rs.getObject("created_at", Instant.class),
                        rs.getObject("expires_at", Instant.class),
                        null
                ))
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

    /**
     * Fetch a binary payload from the database by ID and set that row's state to CONSUMED, making it inaccessible to
     * future queries, and a candidate for removal by a background sweeper job.
     * @param secretId the ID of the row to update
     * @return byte[] containing the binary payload of the secret
     */
    public Optional<byte[]> fetchAndConsume(String secretId) {
        return jdbc.sql("""
                UPDATE secrets
                SET (state, consumed_at) = ('CONSUMED', now())
                WHERE secret_id = :id AND state = 'ACTIVE' AND expires_at > now()
                RETURNING payload
                """)
                .param("id", secretId)
                .query(byte[].class)
                .optional();
    }

    /*
    * Here we separate the deletion queries for audit logging purposes. By running separate queries for CONSUMED rows,
    * and EXPIRED rows, we can reason about WHY the row was deleted, and log it accordingly. Combining both filters
    * in the WHERE clause left no obvious reason as to why the row was deleted.
    */

    /**
     * Sweep the database and remove any rows whose state is CONSUMED.
     * Bounded to app.sweeper.pass-limit
     * @return a list of IDs corresponding to the rows deleted
     */
    public List<String> deleteConsumed() {
        return jdbc.sql("""
                DELETE FROM secrets
                WHERE ctid IN (SELECT ctid FROM secrets WHERE state = 'CONSUMED' LIMIT :limit)
                RETURNING secret_id;
                """)
                .param("limit", appProperties.sweeper().passLimit())
                .query(String.class)
                .list();
    }

    /**
     * Sweep the database and remove any rows whose expiry date has already passed.
     * Bounded to app.sweeper.pass-limit
     * @return a list of IDs corresponding to the rows deleted
     */
    public List<String> deleteExpired() {
        return jdbc.sql("""
                DELETE FROM secrets
                WHERE ctid IN (SELECT ctid FROM secrets WHERE expires_at < now() LIMIT :limit)
                RETURNING secret_id;
                """)
                .param("limit", appProperties.sweeper().passLimit())
                .query(String.class)
                .list();
    }
}
