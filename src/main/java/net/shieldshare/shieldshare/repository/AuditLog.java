package net.shieldshare.shieldshare.repository;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.model.AuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;

@Repository
@RequiredArgsConstructor
public class AuditLog {

    private final JdbcClient jdbc;

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
}
