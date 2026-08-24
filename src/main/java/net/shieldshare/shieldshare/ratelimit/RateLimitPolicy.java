package net.shieldshare.shieldshare.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import net.shieldshare.shieldshare.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RateLimitPolicy {

    private final AppProperties.RateLimit config;

    @Autowired
    public RateLimitPolicy(AppProperties appProperties) {
        this(appProperties.rateLimit());
    }

    RateLimitPolicy(AppProperties.RateLimit config) {
        this.config = config;
    }

    public Bandwidth getRequestBandwidth(RateLimitedRoute route, BreakerState state) {
        return toBandwidth(switch (route) {
            case CREATE -> config.create().requests();
            case FETCH -> tierFor(config.fetch(), state);
            case VALIDATE -> tierFor(config.validate(), state);
            case NONE -> throw new IllegalArgumentException("Invalid route: " + route);
        });
    }

    public Bandwidth getByteBandwidth() {
        return toBandwidth(config.create().bytes());

    }

    private Bandwidth toBandwidth(AppProperties.Limit limit) {
        return BandwidthBuilder.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.refillTokens(), limit.refillPeriod())
                .build();
    }

    /**
     * Returns the limit tier based on the current breaker state. {@code BreakerState.PROBING} uses the normal tier.
     * The reduced allowance that distinguishes it from {@code BreakerState.CLOSED} lives in the breaker's global miss
     * bucket.
     */
    private AppProperties.Limit tierFor(AppProperties.LookupLimits limits, BreakerState state) {
        return state == BreakerState.OPEN ? limits.open() : limits.closed();
    }
}
