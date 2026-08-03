package net.shieldshare.shieldshare.repository;

import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import(SecretsJdbcRepository.class)
// Keep the real container DataSource; without this the slice swaps in an embedded database.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
/*
 * Two reasons this class must NOT run inside a transaction:
 *
 *  1. @JdbcTest is transactional and rolls back by default. The row inserted by a test body would
 *     sit in an uncommitted transaction that the contention test's worker threads - on their own
 *     pooled connections - could never see. Every thread would come back empty.
 *
 *  2. Postgres' now() is transaction_timestamp(), frozen at transaction start. Inside a wrapping
 *     transaction every now() in a test returns the same instant, so expiry behaves nothing like
 *     production.
 *
 * Isolation comes from the TRUNCATE in @BeforeEach instead.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// Hikari defaults to 10 connections. With 32 threads the rest would queue at the pool and never
// reach Postgres together, leaving the contention test measuring Hikari rather than row locks.
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=32")
class SecretsJdbcRepositoryIT extends AbstractPostgresIT {

    private static final int THREADS = 32;

    @Autowired
    private SecretsJdbcRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearTable() {
        jdbc.sql("TRUNCATE TABLE secrets").update();
    }

    private String stateOf(String id) {
        return jdbc.sql("SELECT state FROM secrets WHERE secret_id = :id")
                .param("id", id).query(String.class).single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT count(*) FROM secrets").query(Long.class).single();
    }

    /** Inserts a row directly, bypassing the repository, so expiry and state can be set freely. */
    private void insertRaw(String id, String state, String expiresAtSql) {
        jdbc.sql("""
                        INSERT INTO secrets (secret_id, state, payload, created_at, expires_at)
                        VALUES (:id, :state, :payload, now() - interval '1 day', %s)
                        """.formatted(expiresAtSql))
                .param("id", id)
                .param("state", state)
                .param("payload", new byte[]{1, 2, 3})
                .update();
    }

    // ---------- insert ----------

    @Test
    void insertReturnsAnExpiryRoughlyTtlSecondsAhead() {
        Instant before = Instant.now();

        Optional<Instant> expiry = repository.insert("insert-happy", new byte[]{1, 2, 3}, 3600);

        assertThat(expiry).isPresent();
        assertThat(expiry.get())
                .isBetween(before.plusSeconds(3590), Instant.now().plusSeconds(3610));
    }

    @Test
    void insertReturnsEmptyWhenTheIdAlreadyExists() {
        repository.insert("duplicate-id", new byte[]{1, 2, 3}, 3600);

        Optional<Instant> second = repository.insert("duplicate-id", new byte[]{4, 5, 6}, 3600);

        // ON CONFLICT DO NOTHING means no row is returned - this is the signal the service
        // retries on. It must not overwrite the original payload either.
        assertThat(second).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void insertRejectsABlobLargerThanTheCheckConstraintAllows() {
        // V2__add_secrets_payload_size_check.sql caps octet_length at 1052701.
        byte[] tooBig = new byte[1_052_702];

        assertThatThrownBy(() -> repository.insert("oversized", tooBig, 3600))
                .hasMessageContaining("check_payload_size");
    }

    // ---------- validate ----------

    @Test
    void validateFindsAnActiveUnexpiredSecret() {
        repository.insert("active-id", new byte[]{1, 2, 3}, 3600);

        assertThat(repository.validate("active-id")).contains("active-id");
    }

    @Test
    void validateIgnoresAConsumedSecret() {
        insertRaw("consumed-id", "CONSUMED", "now() + interval '1 hour'");

        assertThat(repository.validate("consumed-id")).isEmpty();
    }

    @Test
    void validateIgnoresAnExpiredSecret() {
        insertRaw("expired-id", "ACTIVE", "now() - interval '1 minute'");

        assertThat(repository.validate("expired-id")).isEmpty();
    }

    @Test
    void validateIgnoresAnUnknownId() {
        assertThat(repository.validate("never-existed")).isEmpty();
    }

    // ---------- fetchAndConsume ----------

    @Test
    void fetchAndConsumeReturnsThePayloadOnceThenNeverAgain() {
        byte[] payload = "the secret".getBytes(StandardCharsets.UTF_8);
        repository.insert("fetch-once", payload, 3600);

        Optional<byte[]> first = repository.fetchAndConsume("fetch-once");
        // Unwrap before asserting. AssertJ's OptionalAssert.contains() compares with equals(),
        // and byte[].equals() is reference identity - it would fail on an identical copy.
        // assertThat(byte[]) gives a ByteArrayAssert, which compares contents.
        assertThat(first).isPresent();
        assertThat(first.get()).isEqualTo(payload);

        assertThat(repository.fetchAndConsume("fetch-once")).isEmpty();

        assertThat(stateOf("fetch-once")).isEqualTo("CONSUMED");
    }

    @Test
    void fetchAndConsumeIgnoresAnExpiredSecret() {
        insertRaw("expired-fetch", "ACTIVE", "now() - interval '1 minute'");

        assertThat(repository.fetchAndConsume("expired-fetch")).isEmpty();
        // An expired secret must stay ACTIVE so the sweeper deletes it on the expiry branch.
        assertThat(stateOf("expired-fetch")).isEqualTo("ACTIVE");
    }

    // ---------- contention ----------

    /*
     * The core guarantee: a secret can be consumed exactly once, even when many requests race.
     *
     * fetchAndConsume is a single UPDATE ... WHERE state = 'ACTIVE' ... RETURNING payload. Under
     * READ COMMITTED, a writer that blocks on the row lock re-evaluates its WHERE clause against
     * the newly committed row version once the lock is released, sees state = 'CONSUMED', matches
     * zero rows, and returns nothing. Exactly one caller can win.
     */
    @RepeatedTest(20)
    void onlyOneCallerCanEverConsumeASecret() throws Exception {
        byte[] payload = "only once".getBytes(StandardCharsets.UTF_8);
        repository.insert("contended", payload, 3600);

        CyclicBarrier startGate = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Optional<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    // Park every thread here so they all hit the UPDATE at once. Without this
                    // they trickle in and the first one is done before the last one starts.
                    startGate.await(10, TimeUnit.SECONDS);
                    return repository.fetchAndConsume("contended");
                }));
            }

            List<byte[]> winners = new ArrayList<>();
            for (Future<Optional<byte[]>> future : futures) {
                future.get(30, TimeUnit.SECONDS).ifPresent(winners::add);
            }

            assertThat(winners).hasSize(1);
            assertThat(winners.get(0)).isEqualTo(payload);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stateOf("contended")).isEqualTo("CONSUMED");
        assertThat(jdbc.sql("SELECT consumed_at FROM secrets WHERE secret_id = 'contended'")
                .query(Instant.class).single()).isNotNull();
    }

    // ---------- deleteConsumedOrExpired ----------

    @Test
    void deleteConsumedOrExpiredRemovesDeadRowsAndSparesLiveOnes() {
        insertRaw("dead-consumed", "CONSUMED", "now() + interval '1 hour'");
        insertRaw("dead-expired", "ACTIVE", "now() - interval '1 minute'");
        repository.insert("still-alive", new byte[]{1, 2, 3}, 3600);

        int deleted = repository.deleteConsumedOrExpired();

        assertThat(deleted).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(repository.validate("still-alive")).contains("still-alive");
    }

    @Test
    void deleteConsumedOrExpiredIsANoOpWhenEverythingIsLive() {
        repository.insert("alive-one", new byte[]{1, 2, 3}, 3600);
        repository.insert("alive-two", new byte[]{4, 5, 6}, 3600);

        assertThat(repository.deleteConsumedOrExpired()).isZero();
        assertThat(rowCount()).isEqualTo(2);
    }
}
