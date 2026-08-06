package net.shieldshare.shieldshare.repository;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.model.AuditEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditLog {

    private final JdbcClient jdbc;

    public int record(AuditEvent event) {
        return jdbc.sql("""
                INSERT INTO audit_logs ( event_id, event_type, resource_id, source_ip, timestamp )
                VALUES ( :eventId, :eventType, :secretId, :sourceIp, :timestamp )
                """)
                .param("eventId", event.getEventId())
                .param("eventType", event.getEventType())
                .param("secretId", event.getSecretId())
                .param("sourceIp", event.getSourceIp())
                .param("timestamp", event.getTimestamp())
                .update();
    }
}
