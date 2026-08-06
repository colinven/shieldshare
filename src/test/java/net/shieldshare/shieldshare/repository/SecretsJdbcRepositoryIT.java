package net.shieldshare.shieldshare.repository;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.model.Secret;
import net.shieldshare.shieldshare.model.SecretState;
import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

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
    // Mirrors the check_payload_size constraint in V2__add_secrets_payload_size_check.sql.
    private static final int MAX_BLOB_BYTES = 1_052_701;

    @MockitoBean
    private AppProperties appProperties;

    @Autowired
    private SecretsJdbcRepository repository;

    @Autowired
    private JdbcClient jdbc;


    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE secrets").update();
        Mockito.when(appProperties.sweeper()).thenReturn(new AppProperties.Sweeper(2500));
    }

    private String stateOf(String id) {
        return jdbc.sql("SELECT state FROM secrets WHERE secret_id = :id")
                .param("id", id).query(String.class).single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT count(*) FROM secrets").query(Long.class).single();
    }

    private boolean hasNoConsumedAt(String id) {
        return jdbc.sql("SELECT consumed_at IS NULL FROM secrets WHERE secret_id = :id")
                .param("id", id).query(Boolean.class).single();
    }

    private OffsetDateTime consumedAtOf(String id) {
        return jdbc.sql("SELECT consumed_at FROM secrets WHERE secret_id = :id")
                .param("id", id).query(OffsetDateTime.class).single();
    }

    private byte[] storedPayloadOf(String id) {
        return jdbc.sql("SELECT payload FROM secrets WHERE secret_id = :id")
                .param("id", id).query(byte[].class).single();
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

        Optional<Secret> record = repository.insert("insert-happy", new byte[]{1, 2, 3}, 3600);

        assertThat(record).isPresent();
        assertThat(record.get().expiresAt())
                .isBetween(before.plusSeconds(3590), Instant.now().plusSeconds(3610));
    }

    @Test
    void insertReturnsEmptyWhenTheIdAlreadyExists() {
        repository.insert("duplicate-id", new byte[]{1, 2, 3}, 3600);

        Optional<Secret> second = repository.insert("duplicate-id", new byte[]{4, 5, 6}, 3600);

        // ON CONFLICT DO NOTHING means no row is returned - this is the signal the service
        // retries on. It must not overwrite the original payload either.
        assertThat(second).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void insertRejectsABlobLargerThanTheCheckConstraintAllows() {
        // V2__add_secrets_payload_size_check.sql caps octet_length at 1052701.
        byte[] tooBig = new byte[MAX_BLOB_BYTES + 1];

        assertThatThrownBy(() -> repository.insert("oversized", tooBig, 3600))
                .hasMessageContaining("check_payload_size");
    }

    /*
     * The constraint is `octet_length(payload) <= 1052701`, so a blob of exactly that size must be
     * accepted. Without this the over-limit test alone would still pass if the DB cap drifted down.
     */
    @Test
    void insertAcceptsABlobExactlyAtTheCheckConstraintLimit() {
        assertThat(repository.insert("at-limit", new byte[MAX_BLOB_BYTES], 3600)).isPresent();
    }

    @Test
    void insertMirrorsBackTheRowItWroteWithoutThePayload() {
        Optional<Secret> record = repository.insert("insert-shape", new byte[]{1, 2, 3}, 3600);

        assertThat(record).isPresent();
        assertThat(record.get().id()).isEqualTo("insert-shape");
        assertThat(record.get().state()).isEqualTo(SecretState.ACTIVE);
        assertThat(record.get().createdAt()).isNotNull();
        assertThat(record.get().expiresAt()).isAfter(record.get().createdAt());
        /*
         * The row mapper deliberately leaves payload null. A creation response has no business
         * carrying the ciphertext back out of the database.
         */
        assertThat(record.get().payload()).isNull();
        assertThat(record.get().consumedAt()).isNull();
    }

    @Test
    void insertStoresThePayloadBytesVerbatim() {
        // Includes a zero and a high byte, which are the ones a bad bytea binding tends to mangle.
        byte[] payload = {0, 1, 2, 127, -1, -128};

        repository.insert("payload-roundtrip", payload, 3600);

        assertThat(storedPayloadOf("payload-roundtrip")).isEqualTo(payload);
    }

    @Test
    void insertAcceptsAnEmptyPayload() {
        // payload is NOT NULL, but a zero-length bytea is a legal value and must not be rejected.
        assertThat(repository.insert("empty-payload", new byte[0], 3600)).isPresent();
        assertThat(storedPayloadOf("empty-payload")).isEmpty();
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

    /*
     * validate() exists to prove a secret is there without burning it. If it ever consumed the row,
     * the "is this link still good?" check would destroy the very secret it was asked about.
     */
    @Test
    void validateLeavesTheSecretUsable() {
        byte[] payload = "still here".getBytes(StandardCharsets.UTF_8);
        repository.insert("peek-only", payload, 3600);

        repository.validate("peek-only");
        repository.validate("peek-only");

        assertThat(stateOf("peek-only")).isEqualTo("ACTIVE");
        assertThat(hasNoConsumedAt("peek-only")).isTrue();

        Optional<byte[]> fetched = repository.fetchAndConsume("peek-only");
        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(payload);
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
        assertThat(hasNoConsumedAt("fetch-once")).isFalse();
    }

    @Test
    void fetchAndConsumeIgnoresAnExpiredSecret() {
        insertRaw("expired-fetch", "ACTIVE", "now() - interval '1 minute'");

        assertThat(repository.fetchAndConsume("expired-fetch")).isEmpty();
        // An expired secret must stay ACTIVE so the sweeper deletes it on the expiry branch.
        assertThat(stateOf("expired-fetch")).isEqualTo("ACTIVE");
        assertThat(hasNoConsumedAt("expired-fetch")).isTrue();
    }

    @Test
    void fetchAndConsumeIgnoresAnUnknownId() {
        assertThat(repository.fetchAndConsume("never-existed")).isEmpty();
    }

    /*
     * A replayed fetch must not touch the row. Bumping consumed_at on the second call would
     * rewrite history: the audit trail says the secret was read at a time nobody read it.
     */
    @Test
    void fetchAndConsumeLeavesConsumedAtAloneOnAReplay() {
        repository.insert("replayed", new byte[]{1, 2, 3}, 3600);
        repository.fetchAndConsume("replayed");
        OffsetDateTime firstConsumedAt = consumedAtOf("replayed");

        assertThat(repository.fetchAndConsume("replayed")).isEmpty();

        assertThat(consumedAtOf("replayed")).isEqualTo(firstConsumedAt);
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

        List<String> expiredDeleted = repository.deleteExpired();
        List<String> consumedDeleted = repository.deleteConsumed();

        /*
         * The returned IDs are what the service turns into audit events, so each list has to name
         * the right rows. Asserting only the total count would pass even if the two queries swapped
         * their result sets and every deletion was logged under the wrong reason.
         */
        assertThat(expiredDeleted).containsExactly("dead-expired");
        assertThat(consumedDeleted).containsExactly("dead-consumed");
        assertThat(rowCount()).isEqualTo(1);
        assertThat(repository.validate("still-alive")).contains("still-alive");
    }

    /*
     * A row can be both CONSUMED and past its expiry. Whichever sweep runs first claims it, and the
     * service runs deleteExpired() first - so it is audited as an expiry, not a consumption. This
     * pins the attribution down so a reordering in the service does not silently change the logs.
     */
    @Test
    void deleteExpiredClaimsARowThatIsBothConsumedAndExpired() {
        insertRaw("consumed-and-expired", "CONSUMED", "now() - interval '1 minute'");

        List<String> expiredDeleted = repository.deleteExpired();
        List<String> consumedDeleted = repository.deleteConsumed();

        assertThat(expiredDeleted).containsExactly("consumed-and-expired");
        assertThat(consumedDeleted).isEmpty();
        assertThat(rowCount()).isZero();
    }

    @Test
    void deleteConsumedLeavesExpiredRowsForTheOtherSweep() {
        insertRaw("expired-only", "ACTIVE", "now() - interval '1 minute'");

        assertThat(repository.deleteConsumed()).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void deleteExpiredLeavesLiveConsumedRowsForTheOtherSweep() {
        insertRaw("consumed-only", "CONSUMED", "now() + interval '1 hour'");

        assertThat(repository.deleteExpired()).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
    }

    /*
     * Both sweeps are bounded by app.sweeper.pass-limit so one pass cannot lock up the table on a
     * huge backlog. The leftovers are picked up by the next scheduled pass.
     */
    @Test
    void sweepsDeleteNoMoreThanThePassLimitInOneGo() {
        Mockito.when(appProperties.sweeper()).thenReturn(new AppProperties.Sweeper(2));
        for (int i = 0; i < 5; i++) {
            insertRaw("expired-" + i, "ACTIVE", "now() - interval '1 minute'");
            insertRaw("consumed-" + i, "CONSUMED", "now() + interval '1 hour'");
        }

        assertThat(repository.deleteExpired()).hasSize(2);
        assertThat(repository.deleteConsumed()).hasSize(2);
        assertThat(rowCount()).isEqualTo(6);

        // A second pass keeps chipping away rather than stalling on the same rows.
        assertThat(repository.deleteExpired()).hasSize(2);
        assertThat(repository.deleteConsumed()).hasSize(2);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void deleteConsumedOrExpiredIsANoOpWhenEverythingIsLive() {
        repository.insert("alive-one", new byte[]{1, 2, 3}, 3600);
        repository.insert("alive-two", new byte[]{4, 5, 6}, 3600);

        List<String> expiredDeleted = repository.deleteExpired();
        List<String> consumedDeleted = repository.deleteConsumed();
        int deletedCount = expiredDeleted.size() + consumedDeleted.size();

        assertThat(deletedCount).isEqualTo(0);
        assertThat(rowCount()).isEqualTo(2);
    }
}
