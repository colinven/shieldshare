package net.shieldshare.shieldshare.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.model.AuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.ZoneOffset;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AuditLog {

    private final JdbcClient jdbc;
    private final AppProperties appProperties;

    public int record(AuditEvent event) {
        return jdbc.sql("""
                INSERT INTO audit_logs (event_id, event_type, resource_id, source_ip, event_timestamp)
                VALUES (:eventId, :eventType, :secretId, :sourceIp, :eventTimestamp)
                """)
                .param("eventId", event.getEventId())
                .param("eventType", event.getEventType().name())
                .param("secretId", event.getSecretId())
                .param("sourceIp", event.getSourceIp())
                // pgjdbc can't infer a SQL type for Instant, so hand it an OffsetDateTime at UTC.
                .param("eventTimestamp", event.getTimestamp().atOffset(ZoneOffset.UTC))
                .update();
    }

    public int recordAccessAttempt(AuditEvent event) {
        return jdbc.sql("""
                INSERT INTO unsuccessful_access_attempts (event_id, resource_id, source_ip, event_timestamp)
                VALUES (:eventId, :secretId, :sourceIp, :eventTimestamp)
                """)
                .param("eventId", event.getEventId())
                .param("secretId", event.getSecretId())
                .param("sourceIp", event.getSourceIp())
                .param("eventTimestamp", event.getTimestamp().atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Delete audit log rows older than app.sweeper.audit-log-retention.
     */
    public void purgeOldAuditLogs() {
        int deleted = jdbc.sql("""
                DELETE FROM audit_logs
                WHERE event_timestamp < now() - make_interval(secs => :retentionSeconds)
                """)
                .param("retentionSeconds", retentionSeconds(appProperties.sweeper().auditLogRetention()))
                .update();
        log.info("Purged {} audit logs", deleted);
    }

    /**
     * Delete access attempt rows older than app.sweeper.access-attempt-retention. Each pass is
     * bounded to app.sweeper.pass-limit, so the loop keeps going until a pass deletes nothing.
     */
    public void purgeUnsuccessfulAccessAttempts() {
        String query = """
                DELETE FROM unsuccessful_access_attempts
                WHERE ctid IN (
                    SELECT ctid FROM unsuccessful_access_attempts
                    WHERE event_timestamp < now() - make_interval(secs => :retentionSeconds)
                    LIMIT :limit
                )
                """;
        double retentionSeconds = retentionSeconds(appProperties.sweeper().accessAttemptRetention());
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(query)
                    .param("retentionSeconds", retentionSeconds)
                    .param("limit", appProperties.sweeper().passLimit())
                    .update();
            total += deleted;
        } while (deleted > 0);
        log.info("Purged {} unsuccessful access attempt logs", total);
    }

    /*
     * make_interval takes a plain number rather than an interval literal, which keeps the window a
     * bound parameter instead of string-concatenated SQL. secs is a double, so a Duration converts
     * cleanly at any precision the config uses.
     */
    private double retentionSeconds(Duration retention) {
        return retention.toMillis() / 1000d;
    }
}
