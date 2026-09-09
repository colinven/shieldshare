package net.shieldshare.shieldshare.controller;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.dto.response.ClientConfigResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final Duration CACHE_WINDOW = Duration.ofMinutes(5);

    private final AppProperties appProperties;

    @GetMapping
    public ResponseEntity<ClientConfigResponse> getConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_WINDOW).cachePublic())
                .body(ClientConfigResponse.from(appProperties));
    }
}
