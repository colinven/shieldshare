package net.shieldshare.shieldshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Typed view of the {@code app.*} configuration tree. Constructor-bound, so it can be built
 * directly in tests without a Spring context.
 *
 * @param sizeCaps payload size limits
 * @param ttlOptionsSeconds the only TTL values a client is allowed to request
 * @param sweeper config values for the database sweeper
 */
@ConfigurationProperties("app")
public record AppProperties(SizeCaps sizeCaps, List<Integer> ttlOptionsSeconds, Sweeper sweeper) {

    /**
     * @param maxContentBytes plaintext cap, used for client-side UX only
     * @param maxRequestBytes whole-request cap including base64 and JSON overhead
     * @param maxBlobBytes cap on the decoded blob actually stored in the database
     */
    public record SizeCaps(long maxContentBytes, long maxRequestBytes, long maxBlobBytes) {
    }

    /**
     * @param passLimit the max number of rows the sweeper can delete in a single pass, per query.
     * @param auditLogRetention how long a row in audit_logs is kept before the sweeper purges it
     * @param accessAttemptRetention how long a row in unsuccessful_access_attempts is kept. Much
     *                               shorter than the audit log window - failed fetches are mostly
     *                               scanner noise and only stay useful while an incident is fresh.
     */
    public record Sweeper(int passLimit, Duration auditLogRetention, Duration accessAttemptRetention) {}
}
