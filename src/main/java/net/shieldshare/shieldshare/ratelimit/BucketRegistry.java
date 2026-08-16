package net.shieldshare.shieldshare.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.*;
import net.shieldshare.shieldshare.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class BucketRegistry {

    private final Cache<String, BucketWithState> buckets;
    private final TimeMeter timeMeter;

    public BucketRegistry(AppProperties appProperties, TimeMeter timeMeter) {
        this(appProperties.rateLimit().cache(), timeMeter);
    }

    BucketRegistry(AppProperties.BucketCache config, TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(config.expireAfterAccess())
                .maximumSize(config.maxSize())
                .ticker(timeMeter::currentTimeNanos)
                .build();
    }

    /**
     * Retrieve a {@code BucketWithState} from the cache by {@code key}. If a bucket already exists, it will be returned.
     * Otherwise, one will be created with the {@code state} and {@code bandwidth} provided.
     * @param key the key of the cache entry
     * @param state the current {@code BreakerState} of the {@code LookupCircuitBreaker} at the time of invocation
     * @param bandwidth the correct {@code Bandwidth} according to the breaker state and route being accessed
     */
    public BucketWithState getBucket(String key, BreakerState state, Bandwidth bandwidth) {
        return buckets.get(key, k -> new BucketWithState(
                Bucket.builder()
                        .addLimit(bandwidth)
                        .withCustomTimePrecision(timeMeter)
                        .build(),
                state));
    }

    /**
     * Update and cache a {@code BucketWithState}'s internal state and {@code BucketConfiguration} to include the provided
     * {@code state} and {@code bandwidth}.
     * @param key the key used to update the cache entry
     * @param b the {@code BucketWithState} object to update
     * @param state the {@code BreakerState} to update the bucket to
     * @param bandwidth the bandwidth used to update the {@code BucketConfiguration}
     */
    public void updateBucketState(String key, BucketWithState b, BreakerState state, Bandwidth bandwidth) {
        b.getBucket().replaceConfiguration(configFor(bandwidth), strategyFor(b.getState(), state));
        b.setState(state);
        buckets.put(key, b);
    }

    private BucketConfiguration configFor(Bandwidth bandwidth) {
        return BucketConfiguration.builder().addLimit(bandwidth).build();
    }

    /**
     * Returns the appropriate {@code TokensInheritanceStrategy} for the transition from {@code prevState -> state}.
     * For transitions that result in a TIGHTENING of allowance, tokens are inherited
     * {@code PROPORTIONALLY}, whereas for transitions that result in a WIDENING of allowance, tokens are inherited
     * {@code AS-IS}.
     */
    private TokensInheritanceStrategy strategyFor(BreakerState prevState, BreakerState state) {
        return switch (prevState) {
            case CLOSED -> TokensInheritanceStrategy.PROPORTIONALLY;
            case OPEN -> TokensInheritanceStrategy.AS_IS;
            case PROBING -> state == BreakerState.OPEN
                    ? TokensInheritanceStrategy.PROPORTIONALLY
                    : TokensInheritanceStrategy.AS_IS;
        };
    }

    public long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
