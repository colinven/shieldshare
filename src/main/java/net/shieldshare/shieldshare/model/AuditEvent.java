package net.shieldshare.shieldshare.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditEvent {

    private UUID eventId;
    private EventType eventType;
    private String secretId;
    private String sourceIp;
    private Instant timestamp;

    public enum EventType {
        SECRET_CREATED,
        SECRET_VALIDATED,
        SECRET_FETCHED_SUCCESS,
        SECRET_FETCHED_FAILURE,
        SECRET_DELETED_EXPIRED,
        SECRET_DELETED_CONSUMED
    }

    public static AuditEvent secretCreated(String secretId, String sourceIp, Instant timestamp) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_CREATED)
                .secretId(secretId)
                .sourceIp(sourceIp)
                .timestamp(timestamp)
                .build();
    }
    public static AuditEvent secretValidated(String secretId, String sourceIp) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_VALIDATED)
                .secretId(secretId)
                .sourceIp(sourceIp)
                .timestamp(Instant.now())
                .build();
    }
    public static AuditEvent secretFetchedSuccess(String secretId, String sourceIp) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_FETCHED_SUCCESS)
                .secretId(secretId)
                .sourceIp(sourceIp)
                .timestamp(Instant.now())
                .build();
    }
    public static AuditEvent secretFetchedFailure(String secretId, String sourceIp) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_FETCHED_FAILURE)
                .secretId(secretId)
                .sourceIp(sourceIp)
                .timestamp(Instant.now())
                .build();
    }
    public static AuditEvent secretDeletedExpired(String secretId) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_DELETED_EXPIRED)
                .secretId(secretId)
                .timestamp(Instant.now())
                .build();
    }
    public static AuditEvent secretDeletedConsumed(String secretId) {
        return AuditEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(EventType.SECRET_DELETED_CONSUMED)
                .secretId(secretId)
                .timestamp(Instant.now())
                .build();
    }
}
