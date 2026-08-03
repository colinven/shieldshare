package net.shieldshare.shieldshare.service;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretPayloadResponse;
import net.shieldshare.shieldshare.exception.InvalidSecretException;
import net.shieldshare.shieldshare.exception.MalformedPayloadException;
import net.shieldshare.shieldshare.exception.OversizedPayloadException;
import net.shieldshare.shieldshare.exception.SecretInsertionException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecretsServiceTest {

    private static final long MAX_BLOB_BYTES = 1_052_701L;
    private static final int VALID_TTL = 300;
    private static final Instant DB_EXPIRY = Instant.parse("2026-08-03T12:00:00Z");

    @Mock
    private SecretsJdbcRepository secretsRepository;

    private SecretsService secretsService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.SizeCaps(1_048_576L, 1_573_000L, MAX_BLOB_BYTES),
                List.of(300, 3600, 86400, 604800));
        secretsService = new SecretsService(secretsRepository, new SecureRandom(), properties);
    }

    /** Base64 of a zero-filled blob of the given decoded size. */
    private static String payloadOfBytes(int decodedSize) {
        return Base64.getEncoder().encodeToString(new byte[decodedSize]);
    }

    // ---------- createSecret ----------

    @Test
    void createSecretRejectsTtlOutsideTheConfiguredOptions() {
        CreateSecretRequest request = new CreateSecretRequest(payloadOfBytes(16), 999);

        assertThatThrownBy(() -> secretsService.createSecret(request))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("Invalid TTL option");

        verifyNoInteractions(secretsRepository);
    }

    @Test
    void createSecretRejectsAPayloadThatIsNotBase64() {
        CreateSecretRequest request = new CreateSecretRequest("not base64 !!!", VALID_TTL);

        assertThatThrownBy(() -> secretsService.createSecret(request))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("Could not decode payload");

        verifyNoInteractions(secretsRepository);
    }

    @Test
    void createSecretRejectsAPayloadOneByteOverTheBlobLimit() {
        CreateSecretRequest request =
                new CreateSecretRequest(payloadOfBytes((int) MAX_BLOB_BYTES + 1), VALID_TTL);

        assertThatThrownBy(() -> secretsService.createSecret(request))
                .isInstanceOf(OversizedPayloadException.class);

        verifyNoInteractions(secretsRepository);
    }

    /*
     * The guard is `length > maxBlobBytes`, so a payload of exactly the cap must be accepted.
     * Testing only the over-limit case would let an off-by-one slip in later.
     */
    @Test
    void createSecretAcceptsAPayloadExactlyAtTheBlobLimit() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.of(DB_EXPIRY));

        CreateSecretRequest request =
                new CreateSecretRequest(payloadOfBytes((int) MAX_BLOB_BYTES), VALID_TTL);

        assertThatCode(() -> secretsService.createSecret(request)).doesNotThrowAnyException();
    }

    @Test
    void createSecretReturnsTheDatabaseExpiryAndAFreshUrlSafeId() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(3600)))
                .thenReturn(Optional.of(DB_EXPIRY));

        CreateSecretResponse response =
                secretsService.createSecret(new CreateSecretRequest(payloadOfBytes(32), 3600));

        /*
         * The database clock is the source of truth for expiry (see the comment in
         * SecretsService#createSecret). The service must hand back exactly what the
         * repository returned, never a value recomputed in the JVM.
         */
        assertThat(response.expiresAt()).isEqualTo(DB_EXPIRY);

        // 16 random bytes, unpadded URL-safe base64 -> 22 chars, and it must fit VARCHAR(24).
        assertThat(response.secretId()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void createSecretStoresTheDecodedPayloadNotTheBase64Text() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.of(DB_EXPIRY));

        byte[] original = {9, 8, 7, 6, 5, 4};
        String encoded = Base64.getEncoder().encodeToString(original);

        secretsService.createSecret(new CreateSecretRequest(encoded, VALID_TTL));

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(secretsRepository).insert(anyString(), stored.capture(), eq(VALID_TTL));
        assertThat(stored.getValue()).isEqualTo(original);
    }

    @Test
    void createSecretRetriesWithADifferentIdAfterAnIdCollision() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(DB_EXPIRY));

        CreateSecretResponse response =
                secretsService.createSecret(new CreateSecretRequest(payloadOfBytes(16), VALID_TTL));

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(secretsRepository, times(2)).insert(ids.capture(), any(byte[].class), eq(VALID_TTL));

        /*
         * The retry must use a NEW id. Asserting only "two calls happened" would still pass
         * if the retry re-sent the colliding id, which in production would never converge.
         */
        assertThat(ids.getAllValues()).doesNotHaveDuplicates();
        assertThat(response.secretId()).isEqualTo(ids.getAllValues().get(1));
    }

    @Test
    void createSecretGivesUpAfterThreeFailedInsertAttempts() {
        when(secretsRepository.insert(anyString(), any(byte[].class), eq(VALID_TTL)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretsService.createSecret(
                new CreateSecretRequest(payloadOfBytes(16), VALID_TTL)))
                .isInstanceOf(SecretInsertionException.class);

        verify(secretsRepository, times(3)).insert(anyString(), any(byte[].class), eq(VALID_TTL));
    }

    // ---------- validateSecret ----------

    @Test
    void validateSecretReportsValidWhenTheRepositoryFindsTheId() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.of("some-id"));

        assertThat(secretsService.validateSecret("some-id").valid()).isTrue();
    }

    @Test
    void validateSecretReportsInvalidWhenTheRepositoryFindsNothing() {
        when(secretsRepository.validate("some-id")).thenReturn(Optional.empty());

        assertThat(secretsService.validateSecret("some-id").valid()).isFalse();
    }

    // ---------- fetchSecret ----------

    @Test
    void fetchSecretReturnsThePayloadBase64Encoded() {
        byte[] payload = {1, 2, 3, 4, 5};
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.of(payload));

        SecretPayloadResponse response = secretsService.fetchSecret("some-id");

        // The encoder is unpadded, so decode and compare bytes rather than matching strings.
        assertThat(Base64.getDecoder().decode(response.payload())).isEqualTo(payload);
    }

    @Test
    void fetchSecretThrowsWhenTheSecretIsExpiredConsumedOrUnknown() {
        when(secretsRepository.fetchAndConsume("some-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretsService.fetchSecret("some-id"))
                .isInstanceOf(InvalidSecretException.class);
    }
}
