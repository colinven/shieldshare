package net.shieldshare.shieldshare.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretPayloadResponse;
import net.shieldshare.shieldshare.dto.response.SecretValidationResponse;
import net.shieldshare.shieldshare.exception.InvalidSecretException;
import net.shieldshare.shieldshare.exception.MalformedPayloadException;
import net.shieldshare.shieldshare.exception.OversizedPayloadException;
import net.shieldshare.shieldshare.exception.SecretInsertionException;
import net.shieldshare.shieldshare.model.AuditEvent;
import net.shieldshare.shieldshare.model.Secret;
import net.shieldshare.shieldshare.repository.AuditLog;
import net.shieldshare.shieldshare.repository.SecretsJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecretsService {

    private final SecretsJdbcRepository secretsRepository;
    private final SecureRandom secureRandom;
    private final AppProperties appProperties;
    private final AuditLog auditLog;

    @Transactional
    public CreateSecretResponse createSecret(CreateSecretRequest request, String clientIp) {

        final int MAX_INSERTION_RETRIES = 3;
        int insertionAttempts = 0;

        if (!appProperties.ttlOptionsSeconds().contains(request.ttlSeconds())) {
            throw new MalformedPayloadException("Invalid TTL option provided");
        }

        // Decode Base64 payload and check that it doesn't violate blob size limit
        byte[] binaryData;
        try {
            binaryData = Base64.getDecoder().decode(request.payload());
        } catch (IllegalArgumentException e) {
            log.warn("Could not decode incoming payload: {}", e.getMessage());
            throw new MalformedPayloadException("Could not decode payload: " + e.getMessage());
        }
        if (binaryData.length > appProperties.sizeCaps().maxBlobBytes()) {
            log.info("Oversized payload rejected for creation. Length: {}", binaryData.length);
            throw new OversizedPayloadException("Payload size exceeds max allowed bytes");
        }
        String secretId = generateSecretId();
        /*Attempt to insert secret into DB. *Unlikely*, but if an ID collision occurs,
        retry with new ID up to MAX_INSERTION_RETRIES. */
        Optional<Secret> insertedSecret = secretsRepository.insert(secretId, binaryData, request.ttlSeconds());
        insertionAttempts++;
        while (insertedSecret.isEmpty() && insertionAttempts < MAX_INSERTION_RETRIES) {
            log.warn("Secret insertion attempt {} failed for ID {}. Retrying...", insertionAttempts, secretId);
            secretId = generateSecretId();
            insertedSecret = secretsRepository.insert(secretId, binaryData, request.ttlSeconds());
            insertionAttempts++;
        }
        // If insertion still failed after retries, we have a different problem. return 500
        if (insertedSecret.isEmpty()) {
            log.error("Secret insertion failed after {} attempts", insertionAttempts);
            throw new SecretInsertionException("Failed to insert secret into database");
        }
        Secret secret = insertedSecret.get();

        auditLog.record(AuditEvent.secretCreated(secret.id(), clientIp, secret.createdAt()));
        log.info("Secret insertion successful for ID {}", secret.id());
        return new CreateSecretResponse(secret.id(), secret.expiresAt());
    }

    @Transactional
    public SecretValidationResponse validateSecret(String secretId, String clientIp) {
        Optional<String> retrievedId = secretsRepository.validate(secretId);
        if (retrievedId.isEmpty()) {
            log.warn("Failed to validate secret with ID {}", secretId);
        } else {
            auditLog.record(AuditEvent.secretValidated(secretId, clientIp));
            log.info("Successfully validated secret with ID {}", secretId);
        }
        return new SecretValidationResponse(retrievedId.isPresent());
    }

    @Transactional(noRollbackFor = InvalidSecretException.class)
    public SecretPayloadResponse fetchSecret(String secretId, String clientIp) {
        Optional<byte[]> payload = secretsRepository.fetchAndConsume(secretId);
        if (payload.isEmpty()) {
            // Secret is expired, already consumed, or ID is invalid
            auditLog.recordAccessAttempt(AuditEvent.secretFetchedFailure(secretId, clientIp));
            log.warn("Failed attempt to fetch secret with ID {}", secretId);
            throw new InvalidSecretException("Secret with ID " + secretId + " does not exist");
        }
        String base64Encoded = Base64.getEncoder().withoutPadding().encodeToString(payload.get());
        auditLog.record(AuditEvent.secretFetchedSuccess(secretId, clientIp));
        log.info("Successfully fetched secret with ID {}", secretId);
        return new SecretPayloadResponse(base64Encoded);
    }

    @Transactional
    public void deleteStaleSecrets() {
        List<String> purgedExpiredIds = secretsRepository.deleteExpired();
        List<String> purgedConsumedIds = secretsRepository.deleteConsumed();
        purgedExpiredIds.stream()
                .map(AuditEvent::secretDeletedExpired)
                .toList()
                .forEach(auditLog::record);
        purgedConsumedIds.stream()
                .map(AuditEvent::secretDeletedConsumed)
                .toList()
                .forEach(auditLog::record);
        if (!purgedExpiredIds.isEmpty()) {
            log.info("Deleted {} expired rows from secrets table", purgedExpiredIds.size());
        }
        if (!purgedConsumedIds.isEmpty()) {
            log.info("Deleted {} consumed rows from secrets table", purgedConsumedIds.size());
        }
    }

    private String generateSecretId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
