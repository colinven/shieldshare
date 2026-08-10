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
 * @param rateLimit config values for the rate limiter
 */
@ConfigurationProperties("app")
public record AppProperties(SizeCaps sizeCaps, List<Integer> ttlOptionsSeconds, Sweeper sweeper, RateLimit rateLimit) {

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

    /**
     * The shape of a token bucket
     * @param capacity burst size - the most that can be spent at once
     * @param refillTokens the number of tokens refilled in a single refill period
     * @param refillPeriod the duration of a single refill period
     */
    public record Limit(long capacity, long refillTokens, Duration refillPeriod) {}

    /**
     * Limit shape for secret creation - metered for both request count and byte count
     * @param requests
     * @param bytes
     */
    public record CreateLimits(Limit requests, Limit bytes) {}

    /**
     * Limit shape for lookup endpoints, with separate configurations for open and closed - in correlation with
     * the state of the {@link net.shieldshare.shieldshare.ratelimit.LookupCircuitBreaker}
     * @param closed limit config for {@link net.shieldshare.shieldshare.ratelimit.BreakerState#CLOSED}
     *               and {@link net.shieldshare.shieldshare.ratelimit.BreakerState#PROBING}
     * @param open limit config for {@link net.shieldshare.shieldshare.ratelimit.BreakerState#OPEN}
     */
    public record LookupLimits(Limit closed, Limit open) {}

    /**
     * Config values for {@link net.shieldshare.shieldshare.ratelimit.LookupCircuitBreaker}
     * @param missCapacity the number of missed lookup requests to be made in a single refill period to trip the breaker
     * @param missRefillPeriod the duration of a single refill period
     * @param cooldown how long the breaker stays open, and how long it probes before closing
     * @param probingCapacity the reduced capacity of missed lookup requests while the breaker is probing, so that
     *                        an attacker trips the breaker within seconds, instead of getting a full missCapacity
     */
    public record Breaker(long missCapacity, Duration missRefillPeriod, Duration cooldown, long probingCapacity) {}

    /**
     * Bounds on a bucket {@link com.github.benmanes.caffeine.cache.Cache}
     * @param maxSize the maximum number of entries in the cache
     * @param expireAfterAccess the duration a bucket must be idle in order for it to be evicted
     */
    public record BucketCache(long maxSize, Duration expireAfterAccess) {}

    /**
     * @param create budgets for 'POST /secrets/create'
     * @param fetch budgets for 'GET /secrets/fetch/{id}'
     * @param validate budgets for 'GET /secrets/fetch/{id}'
     * @param breaker global lookup circuit breaker config values
     * @param cache bounds on the in-memory bucket cache
     */
    public record RateLimit(CreateLimits create, LookupLimits fetch, LookupLimits validate,
                            Breaker breaker, BucketCache cache) {}

}
