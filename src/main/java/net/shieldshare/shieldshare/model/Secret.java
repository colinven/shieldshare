package net.shieldshare.shieldshare.model;

import java.time.Instant;

public record Secret(
        String id,
        SecretState state,
        byte[] payload,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt
) {}
