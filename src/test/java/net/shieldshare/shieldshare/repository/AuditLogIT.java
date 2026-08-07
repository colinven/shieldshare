package net.shieldshare.shieldshare.repository;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.model.AuditEvent;
import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@JdbcTest
@Import(AuditLog.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogIT extends AbstractPostgresIT {

    private static final Duration AUDIT_LOG_RETENTION = Duration.ofDays(90);
    private static final Duration ACCESS_ATTEMPT_RETENTION = Duration.ofDays(7);

    @MockitoBean
    private AppProperties appProperties;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        stubSweeper(2500);
    }

    private void stubSweeper(int passLimit) {
        Mockito.when(appProperties.sweeper()).thenReturn(
                new AppProperties.Sweeper(passLimit, AUDIT_LOG_RETENTION, ACCESS_ATTEMPT_RETENTION));
    }

    private Map<String, Object> auditRow(String resourceId) {
        return jdbc.sql("SELECT * FROM audit_logs WHERE resource_id = :id")
                .param("id", resourceId).query().singleRow();
    }

    private Map<String, Object> accessAttemptRow(String resourceId) {
        return jdbc.sql("SELECT * FROM unsuccessful_access_attempts WHERE resource_id = :id")
                .param("id", resourceId).query().singleRow();
    }

    /*
     * The generic map query hands back a java.sql.Timestamp for a timestamptz column, so read the
     * timestamp with a typed query instead and keep the map for the plain columns.
     */
    private Instant timestampFrom(String table, String resourceId) {
        return jdbc.sql("SELECT event_timestamp FROM " + table + " WHERE resource_id = :id")
                .param("id", resourceId).query(OffsetDateTime.class).single().toInstant();
    }

    private long auditRowsFor(String resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_logs WHERE resource_id = :id")
                .param("id", resourceId).query(Long.class).single();
    }

    private long accessAttemptRowsFor(String resourceId) {
        return jdbc.sql("SELECT count(*) FROM unsuccessful_access_attempts WHERE resource_id = :id")
                .param("id", resourceId).query(Long.class).single();
    }

    // ---------- record ----------

    @Test
    void recordsEventWithCallerSuppliedTimestamp() {
        int rows = auditLog.record(AuditEvent.secretCreated("abc123", "127.0.0.1", Instant.now()));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void recordsEventWithoutSourceIp() {
        int rows = auditLog.record(AuditEvent.secretDeletedExpired("abc124"));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void storedTimestampRoundTripsBackToTheSameInstant() {
        Instant createdAt = Instant.parse("2026-08-06T12:00:00Z");
        auditLog.record(AuditEvent.secretCreated("abc125", "127.0.0.1", createdAt));

        Instant stored = jdbc.sql("SELECT event_timestamp FROM audit_logs WHERE resource_id = 'abc125'")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();

        assertThat(stored).isEqualTo(createdAt);
    }

    /*
     * Every column has to land in the right place. Asserting only "one row was written" would still
     * pass if two params were transposed and the log recorded the wrong event against the wrong id.
     */
    @Test
    void recordWritesEveryColumnFromTheEvent() {
        AuditEvent event = AuditEvent.secretFetchedSuccess("abc126", "10.1.2.3");

        auditLog.record(event);

        Map<String, Object> row = auditRow("abc126");
        assertThat(row.get("event_id")).isEqualTo(event.getEventId());
        assertThat(row.get("event_type")).isEqualTo("SECRET_FETCHED_SUCCESS");
        assertThat(row.get("resource_id")).isEqualTo("abc126");
        assertThat(row.get("source_ip")).isEqualTo("10.1.2.3");
        /*
         * timestamptz stores microseconds, so the nanos on an Instant.now() event get rounded on the
         * way in. Compare with microsecond tolerance rather than demanding an exact match.
         */
        assertThat(timestampFrom("audit_logs", "abc126"))
                .isCloseTo(event.getTimestamp(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void recordLeavesSourceIpNullWhenTheEventHasNone() {
        // Sweeper events act on nobody's behalf, so the column stays NULL rather than "" or "null".
        auditLog.record(AuditEvent.secretDeletedConsumed("abc127"));

        assertThat(auditRow("abc127").get("source_ip")).isNull();
    }

    /*
     * event_type is a VARCHAR with a CHECK list in V4, so the enum and the constraint are two copies
     * of the same truth. This fails the moment someone adds an EventType without a migration.
     */
    @ParameterizedTest
    @EnumSource(AuditEvent.EventType.class)
    void recordAcceptsEveryEventTypeTheEnumDefines(AuditEvent.EventType type) {
        AuditEvent event = AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(type)
                .secretId("abc128")
                .sourceIp("127.0.0.1")
                .timestamp(Instant.now())
                .build();

        assertThat(auditLog.record(event)).isEqualTo(1);
        assertThat(auditRow("abc128").get("event_type")).isEqualTo(type.name());
    }

    /*
     * event_id is the primary key. Replaying an event must be rejected outright - a duplicated audit
     * row would let the same action look like it happened twice.
     */
    @Test
    void recordRejectsADuplicateEventId() {
        AuditEvent event = AuditEvent.secretCreated("abc129", "127.0.0.1", Instant.now());
        auditLog.record(event);

        assertThatThrownBy(() -> auditLog.record(event))
                .hasMessageContaining("audit_logs_pkey");
    }

    @Test
    void recordDoesNotTouchTheAccessAttemptsTable() {
        auditLog.record(AuditEvent.secretFetchedSuccess("abc130", "127.0.0.1"));

        assertThat(auditRowsFor("abc130")).isEqualTo(1);
        assertThat(accessAttemptRowsFor("abc130")).isZero();
    }

    // ---------- recordAccessAttempt ----------

    @Test
    void recordAccessAttemptWritesEveryColumnFromTheEvent() {
        AuditEvent event = AuditEvent.secretFetchedFailure("abc131", "10.9.8.7");

        assertThat(auditLog.recordAccessAttempt(event)).isEqualTo(1);

        Map<String, Object> row = accessAttemptRow("abc131");
        assertThat(row.get("event_id")).isEqualTo(event.getEventId());
        assertThat(row.get("resource_id")).isEqualTo("abc131");
        assertThat(row.get("source_ip")).isEqualTo("10.9.8.7");
        assertThat(timestampFrom("unsuccessful_access_attempts", "abc131"))
                .isCloseTo(event.getTimestamp(), within(1, ChronoUnit.MICROS));
    }

    /*
     * The whole point of the second table is keeping scanner noise out of the main audit log. If a
     * failed fetch ever landed in audit_logs, that separation would be gone.
     */
    @Test
    void recordAccessAttemptDoesNotTouchTheMainAuditLog() {
        auditLog.recordAccessAttempt(AuditEvent.secretFetchedFailure("abc132", "127.0.0.1"));

        assertThat(accessAttemptRowsFor("abc132")).isEqualTo(1);
        assertThat(auditRowsFor("abc132")).isZero();
    }

    @Test
    void recordAccessAttemptRejectsADuplicateEventId() {
        AuditEvent event = AuditEvent.secretFetchedFailure("abc133", "127.0.0.1");
        auditLog.recordAccessAttempt(event);

        assertThatThrownBy(() -> auditLog.recordAccessAttempt(event))
                .hasMessageContaining("unsuccessful_access_attempts_pkey");
    }

    /*
     * Failed fetches are the spammiest thing this service sees. Many attempts against one id have to
     * pile up as separate rows - that volume is the signal an operator is looking for.
     */
    @Test
    void recordAccessAttemptAccumulatesRepeatedProbesAgainstOneId() {
        auditLog.recordAccessAttempt(AuditEvent.secretFetchedFailure("abc134", "127.0.0.1"));
        auditLog.recordAccessAttempt(AuditEvent.secretFetchedFailure("abc134", "127.0.0.1"));
        auditLog.recordAccessAttempt(AuditEvent.secretFetchedFailure("abc134", "10.0.0.9"));

        assertThat(accessAttemptRowsFor("abc134")).isEqualTo(3);
    }

    // ---------- purge helpers ----------

    /*
     * The purge cutoffs are relative to now(), so ages are expressed in days back from the current
     * instant rather than as fixed dates. A hard-coded date would start failing once it aged past
     * the cutoff on its own.
     */
    private void insertAuditLogAged(String resourceId, double daysOld) {
        auditLog.record(AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(AuditEvent.EventType.SECRET_CREATED)
                .secretId(resourceId)
                .sourceIp("127.0.0.1")
                .timestamp(daysAgo(daysOld))
                .build());
    }

    private void insertAccessAttemptAged(String resourceId, double daysOld) {
        auditLog.recordAccessAttempt(AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(AuditEvent.EventType.SECRET_FETCHED_FAILURE)
                .secretId(resourceId)
                .sourceIp("127.0.0.1")
                .timestamp(daysAgo(daysOld))
                .build());
    }

    private Instant daysAgo(double days) {
        return Instant.now().minusMillis((long) (days * 24 * 60 * 60 * 1000));
    }

    private long totalAuditRows() {
        return jdbc.sql("SELECT count(*) FROM audit_logs").query(Long.class).single();
    }

    private long totalAccessAttemptRows() {
        return jdbc.sql("SELECT count(*) FROM unsuccessful_access_attempts").query(Long.class).single();
    }

    // ---------- purgeOldAuditLogs ----------

    @Test
    void purgeOldAuditLogsDeletesRowsPastTheRetentionWindow() {
        insertAuditLogAged("purge01", 120);

        auditLog.purgeOldAuditLogs();

        assertThat(auditRowsFor("purge01")).isZero();
    }

    @Test
    void purgeOldAuditLogsKeepsRowsInsideTheRetentionWindow() {
        insertAuditLogAged("purge02", 10);

        auditLog.purgeOldAuditLogs();

        assertThat(auditRowsFor("purge02")).isEqualTo(1);
    }

    /*
     * 90 days is the retention promise, so the day either side of it is where an off-by-one would
     * show up - either deleting evidence a day early or holding it a day too long.
     */
    @Test
    void purgeOldAuditLogsCutsOffAtNinetyDays() {
        insertAuditLogAged("purge03", 89);
        insertAuditLogAged("purge04", 91);

        auditLog.purgeOldAuditLogs();

        assertThat(auditRowsFor("purge03")).isEqualTo(1);
        assertThat(auditRowsFor("purge04")).isZero();
    }

    @Test
    void purgeOldAuditLogsOnAnEmptyTableIsANoOp() {
        jdbc.sql("DELETE FROM audit_logs").update();

        assertThatCode(auditLog::purgeOldAuditLogs).doesNotThrowAnyException();
        assertThat(totalAuditRows()).isZero();
    }

    /*
     * The two tables have different retention windows on purpose. An audit purge must not reach into
     * the access-attempt table, or old failures would vanish 83 days later than intended.
     */
    @Test
    void purgeOldAuditLogsLeavesAccessAttemptsAlone() {
        insertAccessAttemptAged("purge05", 120);

        auditLog.purgeOldAuditLogs();

        assertThat(accessAttemptRowsFor("purge05")).isEqualTo(1);
    }

    // ---------- purgeUnsuccessfulAccessAttempts ----------

    @Test
    void purgeUnsuccessfulAccessAttemptsDeletesRowsPastTheRetentionWindow() {
        insertAccessAttemptAged("purge06", 30);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(accessAttemptRowsFor("purge06")).isZero();
    }

    @Test
    void purgeUnsuccessfulAccessAttemptsCutsOffAtSevenDays() {
        insertAccessAttemptAged("purge07", 6);
        insertAccessAttemptAged("purge08", 8);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(accessAttemptRowsFor("purge07")).isEqualTo(1);
        assertThat(accessAttemptRowsFor("purge08")).isZero();
    }

    @Test
    void purgeUnsuccessfulAccessAttemptsOnAnEmptyTableIsANoOp() {
        jdbc.sql("DELETE FROM unsuccessful_access_attempts").update();

        assertThatCode(auditLog::purgeUnsuccessfulAccessAttempts).doesNotThrowAnyException();
        assertThat(totalAccessAttemptRows()).isZero();
    }

    @Test
    void purgeUnsuccessfulAccessAttemptsLeavesAuditLogsAlone() {
        insertAuditLogAged("purge09", 30);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(auditRowsFor("purge09")).isEqualTo(1);
    }

    /*
     * This is the reason the method deletes in batches at all. With more old rows than one pass can
     * hold, a single DELETE would leave the tail behind, so the loop has to keep going until a pass
     * deletes nothing. A pass limit of 2 and 5 old rows means at least three passes.
     */
    @Test
    void purgeUnsuccessfulAccessAttemptsKeepsGoingPastOnePass() {
        stubSweeper(2);
        jdbc.sql("DELETE FROM unsuccessful_access_attempts").update();
        for (int i = 0; i < 5; i++) {
            insertAccessAttemptAged("bulk" + i, 30);
        }
        insertAccessAttemptAged("purge10", 1);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(totalAccessAttemptRows()).isEqualTo(1);
        assertThat(accessAttemptRowsFor("purge10")).isEqualTo(1);
    }

    // ---------- retention comes from config ----------

    /*
     * The windows only count as configuration if changing them changes what gets deleted. These two
     * pin that down: with a one-day window, a row the 90-day default would have kept has to go.
     */
    @Test
    void auditLogRetentionWindowComesFromConfig() {
        Mockito.when(appProperties.sweeper()).thenReturn(
                new AppProperties.Sweeper(2500, Duration.ofDays(1), ACCESS_ATTEMPT_RETENTION));
        insertAuditLogAged("purge11", 2);
        insertAuditLogAged("purge12", 0.5);

        auditLog.purgeOldAuditLogs();

        assertThat(auditRowsFor("purge11")).isZero();
        assertThat(auditRowsFor("purge12")).isEqualTo(1);
    }

    @Test
    void accessAttemptRetentionWindowComesFromConfig() {
        Mockito.when(appProperties.sweeper()).thenReturn(
                new AppProperties.Sweeper(2500, AUDIT_LOG_RETENTION, Duration.ofHours(1)));
        insertAccessAttemptAged("purge13", 0.5);
        insertAccessAttemptAged("purge14", 0.01);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(accessAttemptRowsFor("purge13")).isZero();
        assertThat(accessAttemptRowsFor("purge14")).isEqualTo(1);
    }

    /*
     * The two windows are independent knobs. Shrinking the access attempt window to nothing must not
     * drag the audit log's own window down with it.
     */
    @Test
    void theTwoRetentionWindowsAreIndependent() {
        Mockito.when(appProperties.sweeper()).thenReturn(
                new AppProperties.Sweeper(2500, AUDIT_LOG_RETENTION, Duration.ZERO));
        insertAuditLogAged("purge15", 30);
        insertAccessAttemptAged("purge15", 30);

        auditLog.purgeUnsuccessfulAccessAttempts();

        assertThat(accessAttemptRowsFor("purge15")).isZero();
        assertThat(auditRowsFor("purge15")).isEqualTo(1);
    }
}
