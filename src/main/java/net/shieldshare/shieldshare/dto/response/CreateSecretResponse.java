package net.shieldshare.shieldshare.dto.response;

import java.time.Instant;

public record CreateSecretResponse(String secretId, Instant expiresAt) {}
