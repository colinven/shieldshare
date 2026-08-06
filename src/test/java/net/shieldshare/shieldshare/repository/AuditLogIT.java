package net.shieldshare.shieldshare.repository;

import net.shieldshare.shieldshare.model.AuditEvent;
import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcClient jdbc;

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
}
