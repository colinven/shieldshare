package net.shieldshare.shieldshare.background;

import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.repository.AuditLog;
import net.shieldshare.shieldshare.service.SecretsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseSweeper {

    private final SecretsService secretsService;
    private final AuditLog auditLog;

    @Scheduled(fixedRate = 60000)
    public void sweepSecrets() {
        secretsService.deleteStaleSecrets();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void sweepLogs() {
        auditLog.purgeOldAuditLogs();
        auditLog.purgeUnsuccessfulAccessAttempts();
    }
}
