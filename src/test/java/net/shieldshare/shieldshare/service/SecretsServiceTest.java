package net.shieldshare.shieldshare.service;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretPayloadResponse;
import net.shieldshare.shieldshare.exception.InvalidSecretException;
import net.shieldshare.shieldshare.exception.MalformedPayloadException;
import net.shieldshare.shieldshare.exception.OversizedPayloadException;
import net.shieldshare.shieldshare.exception.SecretInsertionException;
import net.shieldshare.shieldshare.model.AuditEvent;
import net.shieldshare.shieldshare.model.Secret;
import net.shieldshare.shieldshare.model.SecretState;
import net.shieldshare.shieldshare.repository.AuditLog;
import net.shieldshare.shieldshare.repository.SecretsJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecretsServiceTest {

    private static final long MAX_BLOB_BYTES = 1_052_701L;
    private static final int VALID_TTL = 300;
    private static final Secret SECRET = new Secret(
            "superRandomGeneratedId",
            SecretState.ACTIVE,
            "superSecretPayload".getBytes(),
            Instant.parse("2026-08-02T12:00:00Z"),
            Instant.parse("2026-08-03T12:00:00Z"),
            null);
    private static final String ip = "127.0.0.1";

    @Mock
    private SecretsJdbcRepository secretsRepository;
    @Mock
    private AuditLog auditLog;

    private SecretsService secretsService;


    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.SizeCaps(1_048_576L, 1_573_000L, MAX_BLOB_BYTES),
                List.of(300, 3600, 86400, 604800),
                new AppProperties.Sweeper(2500));
        secretsService = new SecretsService(secretsRepository, new SecureRandom(), properties, auditLog);
    }

    /** Base64 of a zero-filled blob of the given decoded size. */
    private static String payloadOfBytes(int decodedSize) {
        return Base64.getEncoder().withoutPadding().encodeToString(new byte[decodedSize]);
    }

    // ---------- createSecret ----------

    @Test
    void createSecretRejectsTtlOutsideTheConfiguredOptions() {
        CreateSecretRequest request = new CreateSecretRequest(payloadOfBytes(16), 999);

        assertThatThrownBy(() -> secretsService.createSecret(request, ip))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("Invalid TTL option");

        verifyNoInteractions(secretsRepository);
        // A request that never reached the database is not an auditable event.
        verifyNoInteractions(auditLog);
    }

    @Test
    void createSecretRejectsAPayloadThatIsNotBase64() {
        CreateSecretRequest request = new CreateSecretRequest("not base64 !!!", VALID_TTL);

        assertThatThrownBy(() -> secretsService.createSecret(request, ip))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("Could not decode payload");

        verifyNoInteractions(secretsRepository);
        verifyNoInteractions(auditLog);
    }

    @Test
    void createSecretRejectsAPayloadOneByteOverTheBlobLimit() {
        CreateSecretRequest request =
                new CreateSecretRequest(payloadOfBytes((int) MAX_BLOB_BYTES + 1), VALID_TTL);

        assertThatThrownBy(() -> secretsService.createSecret(request, ip))
                .isInstanceOf(OversizedPayloadException.class);

        verifyNoInteractions(secretsRepository);
        verifyNoInteractions(auditLog);
    }

    /*
     * The guard is `length > maxBlobBytes`, so a payload of exactly the cap must be accepted.
     * Testing only the over-limit case would let an off-by-one slip in later.
     */
    @Test
    void createSecretAcceptsAPayloadExactlyAtTheBlobLimit() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.of(SECRET));

        CreateSecretRequest request =
                new CreateSecretRequest(payloadOfBytes((int) MAX_BLOB_BYTES), VALID_TTL);

        assertThatCode(() -> secretsService.createSecret(request, ip)).doesNotThrowAnyException();
    }

    @Test
    void createSecretReturnsTheDatabaseExpiryAndAFreshUrlSafeId() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(3600)))
                .thenReturn(Optional.of(SECRET));

        CreateSecretResponse response =
                secretsService.createSecret(new CreateSecretRequest(payloadOfBytes(32), 3600), ip);

        /*
         * The database clock is the source of truth for expiry (see the comment in
         * SecretsService#createSecret). The service must hand back exactly what the
         * repository returned, never a value recomputed in the JVM.
         */
        assertThat(response.expiresAt()).isEqualTo(SECRET.expiresAt());

        // 16 random bytes, unpadded URL-safe base64 -> 22 chars, and it must fit VARCHAR(24).
        assertThat(response.secretId()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void createSecretStoresTheDecodedPayloadNotTheBase64Text() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenAnswer(i -> Optional.of(new Secret(
                        i.getArgument(0), SECRET.state(), SECRET.payload(),
                        SECRET.createdAt(), SECRET.expiresAt(), null)));

        byte[] original = {9, 8, 7, 6, 5, 4};
        String encoded = Base64.getEncoder().encodeToString(original);

        secretsService.createSecret(new CreateSecretRequest(encoded, VALID_TTL), ip);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(secretsRepository).insert(anyString(), stored.capture(), eq(VALID_TTL));
        assertThat(stored.getValue()).isEqualTo(original);
    }

    @Test
    void createSecretRetriesWithADifferentIdAfterAnIdCollision() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.empty())
                .thenAnswer(i -> Optional.of(new Secret(
                        i.getArgument(0), SECRET.state(), SECRET.payload(),
                        SECRET.createdAt(), SECRET.expiresAt(), null)));

        CreateSecretResponse response =
                secretsService.createSecret(new CreateSecretRequest(payloadOfBytes(16), VALID_TTL), ip);

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(secretsRepository, times(2)).insert(ids.capture(), any(byte[].class), eq(VALID_TTL));

        /*
         * The retry must use a NEW id. Asserting only "two calls happened" would still pass
         * if the retry re-sent the colliding id, which in production would never converge.
         */
        assertThat(ids.getAllValues()).doesNotHaveDuplicates();
        assertThat(response.secretId()).isEqualTo(ids.getAllValues().get(1));

        // One secret was created, so exactly one audit row - not one per insert attempt.
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, times(1)).record(event.capture());
        assertThat(event.getValue().getSecretId()).isEqualTo(response.secretId());
    }

    @Test
    void createSecretGivesUpAfterThreeFailedInsertAttempts() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretsService.createSecret(
                new CreateSecretRequest(payloadOfBytes(16), VALID_TTL), ip))
                .isInstanceOf(SecretInsertionException.class);

        verify(secretsRepository, times(3)).insert(anyString(), any(byte[].class), eq(VALID_TTL));
        // No secret exists, so there is nothing to audit.
        verifyNoInteractions(auditLog);
    }

    @Test
    void createSecretRecordsACreationEventWithTheStoredIdIpAndDatabaseTimestamp() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenAnswer(i -> Optional.of(new Secret(
                        i.getArgument(0), SECRET.state(), SECRET.payload(),
                        SECRET.createdAt(), SECRET.expiresAt(), null)));

        CreateSecretResponse response =
                secretsService.createSecret(new CreateSecretRequest(payloadOfBytes(16), VALID_TTL), ip);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog).record(event.capture());
        verify(auditLog, never()).recordAccessAttempt(any());

        assertThat(event.getValue().getEventType()).isEqualTo(AuditEvent.EventType.SECRET_CREATED);
        assertThat(event.getValue().getSecretId()).isEqualTo(response.secretId());
        assertThat(event.getValue().getSourceIp()).isEqualTo(ip);
        assertThat(event.getValue().getEventId()).isNotNull();
        // The database clock owns createdAt, so the audit row must carry it, not a JVM Instant.
        assertThat(event.getValue().getTimestamp()).isEqualTo(SECRET.createdAt());
    }

    /*
     * An empty payload decodes to a zero-length array, which is under the cap. It must go
     * through the normal path rather than tripping the decode or size guards.
     */
    @Test
    void createSecretAcceptsAnEmptyPayload() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.of(SECRET));

        assertThatCode(() -> secretsService.createSecret(new CreateSecretRequest("", VALID_TTL), ip))
                .doesNotThrowAnyException();

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(secretsRepository).insert(anyString(), stored.capture(), eq(VALID_TTL));
        assertThat(stored.getValue()).isEmpty();
        verify(auditLog).record(any());
    }

    // ---------- validateSecret ----------

    @Test
    void validateSecretReportsValidWhenTheRepositoryFindsTheId() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.of("some-id"));

        assertThat(secretsService.validateSecret("some-id", ip).valid()).isTrue();
    }

    @Test
    void validateSecretReportsInvalidWhenTheRepositoryFindsNothing() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.empty());

        assertThat(secretsService.validateSecret("some-id", ip).valid()).isFalse();
    }

    @Test
    void validateSecretRecordsAValidationEventWhenTheSecretExists() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.of("some-id"));

        secretsService.validateSecret("some-id", ip);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog).record(event.capture());
        verify(auditLog, never()).recordAccessAttempt(any());

        assertThat(event.getValue().getEventType()).isEqualTo(AuditEvent.EventType.SECRET_VALIDATED);
        assertThat(event.getValue().getSecretId()).isEqualTo("some-id");
        assertThat(event.getValue().getSourceIp()).isEqualTo(ip);
        assertThat(event.getValue().getTimestamp()).isNotNull();
    }

    /*
     * A failed validation is a cheap, spammable probe. It is deliberately not audited at all -
     * not even into unsuccessful_access_attempts, which is reserved for failed fetches.
     */
    @Test
    void validateSecretRecordsNothingWhenTheSecretIsUnknown() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.empty());

        secretsService.validateSecret("some-id", ip);

        verifyNoInteractions(auditLog);
    }

    // ---------- fetchSecret ----------

    @Test
    void fetchSecretReturnsThePayloadBase64Encoded() {
        byte[] payload = {1, 2, 3, 4, 5};
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.of(payload));

        SecretPayloadResponse response = secretsService.fetchSecret("some-id", ip);

        // The encoder is unpadded, so decode and compare bytes rather than matching strings.
        assertThat(Base64.getDecoder().decode(response.payload())).isEqualTo(payload);
    }

    @Test
    void fetchSecretThrowsWhenTheSecretIsExpiredConsumedOrUnknown() {
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretsService.fetchSecret("some-id", ip))
                .isInstanceOf(InvalidSecretException.class);
    }

    @Test
    void fetchSecretRecordsASuccessEventInTheMainAuditLog() {
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.of(new byte[]{1, 2}));

        secretsService.fetchSecret("some-id", ip);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog).record(event.capture());
        verify(auditLog, never()).recordAccessAttempt(any());

        assertThat(event.getValue().getEventType())
                .isEqualTo(AuditEvent.EventType.SECRET_FETCHED_SUCCESS);
        assertThat(event.getValue().getSecretId()).isEqualTo("some-id");
        assertThat(event.getValue().getSourceIp()).isEqualTo(ip);
        assertThat(event.getValue().getTimestamp()).isNotNull();
    }

    /*
     * Failed fetches go to the separate unsuccessful_access_attempts table so that scanners
     * hammering random IDs cannot bloat the main audit log.
     */
    @Test
    void fetchSecretRecordsAFailedAttemptInTheSeparateTable() {
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretsService.fetchSecret("some-id", ip))
                .isInstanceOf(InvalidSecretException.class);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog).recordAccessAttempt(event.capture());
        verify(auditLog, never()).record(any());

        assertThat(event.getValue().getEventType())
                .isEqualTo(AuditEvent.EventType.SECRET_FETCHED_FAILURE);
        assertThat(event.getValue().getSecretId()).isEqualTo("some-id");
        assertThat(event.getValue().getSourceIp()).isEqualTo(ip);
    }

    // ---------- deleteStaleSecrets ----------

    @Test
    void deleteStaleSecretsRecordsOneEventPerPurgedIdTaggedByReason() {
        when(secretsRepository.deleteExpired()).thenReturn(List.of("expired-1", "expired-2"));
        when(secretsRepository.deleteConsumed()).thenReturn(List.of("consumed-1"));

        secretsService.deleteStaleSecrets();

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, times(3)).record(events.capture());
        verify(auditLog, never()).recordAccessAttempt(any());

        assertThat(events.getAllValues())
                .extracting(AuditEvent::getSecretId, AuditEvent::getEventType)
                .containsExactlyInAnyOrder(
                        tuple("expired-1", AuditEvent.EventType.SECRET_DELETED_EXPIRED),
                        tuple("expired-2", AuditEvent.EventType.SECRET_DELETED_EXPIRED),
                        tuple("consumed-1", AuditEvent.EventType.SECRET_DELETED_CONSUMED));

        // The sweeper is not acting on behalf of a caller, so there is no source IP to record.
        assertThat(events.getAllValues()).allSatisfy(e -> assertThat(e.getSourceIp()).isNull());
    }

    @Test
    void deleteStaleSecretsRecordsNothingWhenThereIsNothingToPurge() {
        when(secretsRepository.deleteExpired()).thenReturn(List.of());
        when(secretsRepository.deleteConsumed()).thenReturn(List.of());

        secretsService.deleteStaleSecrets();

        verifyNoInteractions(auditLog);
    }
}
