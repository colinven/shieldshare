package net.shieldshare.shieldshare.background;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.service.SecretsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseSweeper {

    private final SecretsService secretsService;

    @Scheduled(fixedRate = 60000)
    public void sweep() {
        secretsService.deleteStaleSecrets();
    }
}
