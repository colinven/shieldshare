package net.shieldshare.shieldshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Typed view of the {@code app.*} configuration tree. Constructor-bound, so it can be built
 * directly in tests without a Spring context.
 *
 * @param sizeCaps payload size limits
 * @param ttlOptionsSeconds the only TTL values a client is allowed to request
 */
@ConfigurationProperties("app")
public record AppProperties(SizeCaps sizeCaps, List<Integer> ttlOptionsSeconds) {

    /**
     * @param maxContentBytes plaintext cap, used for client-side UX only
     * @param maxRequestBytes whole-request cap including base64 and JSON overhead
     * @param maxBlobBytes cap on the decoded blob actually stored in the database
     */
    public record SizeCaps(long maxContentBytes, long maxRequestBytes, long maxBlobBytes) {
    }
}
