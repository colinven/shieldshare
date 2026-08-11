package net.shieldshare.shieldshare.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.dto.request.CreateSecretRequest;
import net.shieldshare.shieldshare.dto.response.CreateSecretResponse;
import net.shieldshare.shieldshare.dto.response.SecretPayloadResponse;
import net.shieldshare.shieldshare.dto.response.SecretValidationResponse;
import net.shieldshare.shieldshare.service.SecretsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/secrets")
@RequiredArgsConstructor
@Validated
public class SecretsController {

    private final SecretsService secretsService;

    @PostMapping("/create")
    public ResponseEntity<CreateSecretResponse> createSecret(@Valid @RequestBody CreateSecretRequest request,
                                                             HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(secretsService.createSecret(request, http.getRemoteAddr()));
    }

    @GetMapping("/validate/{secretId}")
    public ResponseEntity<SecretValidationResponse> validateSecret(@PathVariable String secretId,
                                                                   HttpServletRequest http) {
        return ResponseEntity.ok(secretsService.validateSecret(secretId, http.getRemoteAddr()));
    }

    @PostMapping("/fetch/{secretId}")
    public ResponseEntity<SecretPayloadResponse> fetchSecret(@PathVariable String secretId,
                                                             HttpServletRequest http) {
        return ResponseEntity.ok(secretsService.fetchSecret(secretId, http.getRemoteAddr()));
    }
}
