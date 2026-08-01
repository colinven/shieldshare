package net.shieldshare.shieldshare.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSecretRequest(

        @NotBlank(message = "Encrypted payload cannot be empty")
        String payload,

        @NotNull(message = "TTL duration cannot be empty")
        @Min(value = 0, message = "TTL duration must not be negative")
        Integer ttlSeconds
) {}
