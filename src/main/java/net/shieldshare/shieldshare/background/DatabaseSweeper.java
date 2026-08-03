package net.shieldshare.shieldshare.background;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.repository.SecretsJdbcRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSweeper {

    private final SecretsJdbcRepository secretsRepository;

    @Scheduled(fixedRate = 60000)
    public void sweep() {
        int rowsDeleted = secretsRepository.deleteConsumedOrExpired();
        if (rowsDeleted > 0) {
            log.info("Deleted {} consumed/expired rows from Secrets table", rowsDeleted);
        }
    }
}
