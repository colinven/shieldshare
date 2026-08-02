package net.shieldshare.shieldshare.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretValidationResponse;
import net.shieldshare.shieldshare.exception.MalformedPayloadException;
import net.shieldshare.shieldshare.exception.OversizedPayloadException;
import net.shieldshare.shieldshare.exception.SecretInsertionException;
import net.shieldshare.shieldshare.repository.SecretsJdbcRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecretsService {

    private final SecretsJdbcRepository secretsRepository;
    private final SecureRandom secureRandom;

    @Value("${app.size-caps.max-blob-bytes}")
    private long maxBlobBytes;

    public CreateSecretResponse createSecret(CreateSecretRequest request) {

        final int MAX_INSERTION_RETRIES = 3;
        int insertionAttempts = 0;

        // Decode Base64 payload and check that it doesn't violate blob size limit
        byte[] binaryData;
        try {
            binaryData = Base64.getDecoder().decode(request.payload());
        } catch (IllegalArgumentException e) {
            log.warn("Could not decode incoming payload: {}", e.getMessage());
            throw new MalformedPayloadException("Could not decode payload: " + e.getMessage());
        }
        if (binaryData.length > maxBlobBytes) {
            log.info("Oversized payload rejected for creation. Length: {}", binaryData.length);
            throw new OversizedPayloadException("Payload size exceeds max allowed bytes");
        }
        String secretId = generateSecretId();
        /*Attempt to insert secret into DB. *Unlikely*, but if an ID collision occurs,
        retry with new ID up to MAX_INSERTION_RETRIES. */
        Optional<Instant> secretExpiration = secretsRepository.insert(secretId, binaryData, request.ttlSeconds());
        insertionAttempts++;
        while (secretExpiration.isEmpty() && insertionAttempts < MAX_INSERTION_RETRIES) {
            log.warn("Secret insertion attempt {} failed for ID {}. Retrying...", insertionAttempts, secretId);
            secretId = generateSecretId();
            secretExpiration = secretsRepository.insert(secretId, binaryData, request.ttlSeconds());
            insertionAttempts++;
        }
        // If insertion still failed after retries, we have a different problem. return 500
        if (secretExpiration.isEmpty()) {
            log.error("Secret insertion failed after {} attempts", insertionAttempts);
            throw new SecretInsertionException("Failed to insert secret into database");
        }
        /* Here we use the expiration value returned from the DB as the source of truth to the user. It more accurately
        represents the exact moment that the database transaction began. If we were to recompute in the JVM, the two
        would drift ever so slightly. Since record expiration is vital to the application, this is the right way to go. */
        log.info("Secret insertion successful for ID {}", secretId);
        return new CreateSecretResponse(secretId, secretExpiration.get());
    }

    public SecretValidationResponse validateSecret(String secretId) {
        Optional<String> retrievedId = secretsRepository.validate(secretId);
        if (retrievedId.isEmpty()) {
            log.warn("Failed to validate secret with ID {}", secretId);
        } else {
            log.info("Successfully validated secret with ID {}", secretId);
        }
        return new SecretValidationResponse(retrievedId.isPresent());
    }

    private String generateSecretId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
