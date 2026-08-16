package net.shieldshare.shieldshare.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BandwidthBuilder;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.ratelimit.support.MutableTimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class BucketRegistryTest {

    private final MutableTimeMeter timeMeter = new MutableTimeMeter();
    private final Bandwidth bandwidth = BandwidthBuilder.builder()
            .capacity(2).refillGreedy(2,Duration.ofMinutes(1)).build();

    private BucketRegistry registryWith(long maxSize, Duration expireAfterAccess) {
        return new BucketRegistry(new AppProperties.BucketCache(maxSize, expireAfterAccess), timeMeter);
    }

    @Test
    void returnsTheSameBucketForTheSameKey() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        BucketWithState first = registry.getBucket("a", BreakerState.CLOSED, bandwidth);
        BucketWithState second = registry.getBucket("a", BreakerState.CLOSED, bandwidth);
        first.getBucket().tryConsume(1);

        assertThat(first.equals(second));
        assertThat(second.getBucket().getAvailableTokens()).isEqualTo(1L);
    }

    @Test
    void keepsDistinctKeysSeparate() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        BucketWithState first = registry.getBucket("a", BreakerState.CLOSED, bandwidth);
        BucketWithState second = registry.getBucket("b", BreakerState.CLOSED, bandwidth);
        first.getBucket().tryConsume(1);

        assertThat(first.equals(second)).isFalse();
        assertThat(second.getBucket().getAvailableTokens()).isEqualTo(2L);
    }

    @Test
    void cacheEvictsExpiredBuckets() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        BucketWithState b = registry.getBucket("a", BreakerState.CLOSED, bandwidth);
        timeMeter.advance(Duration.ofMinutes(11));
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void cacheRespectsMaxSize() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        for (int i = 0; i < 20; i++) {
            registry.getBucket("a" + i, BreakerState.CLOSED, bandwidth);
        }
        assertThat(registry.size()).isEqualTo(10);
    }

    @Test
    void updateBucketStateCorrectlyNarrowsBucketCapacities() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        Bandwidth largerAllowance = BandwidthBuilder.builder()
                .capacity(10).refillGreedy(2, Duration.ofMinutes(1)).build();
        Bandwidth smallerAllowance = BandwidthBuilder.builder()
                .capacity(5).refillGreedy(2, Duration.ofMinutes(1)).build();
        BucketWithState b1 = registry.getBucket("a", BreakerState.CLOSED, largerAllowance);
        BucketWithState b2 = registry.getBucket("b", BreakerState.PROBING, largerAllowance);

        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(10L);
        assertThat(b2.getBucket().getAvailableTokens()).isEqualTo(10L);

        // CLOSED -> OPEN transition (proportional token inheritance)
        registry.updateBucketState("a", b1, BreakerState.OPEN, smallerAllowance);
        // PROBING -> OPEN transition (proportional token inheritance)
        registry.updateBucketState("b", b2, BreakerState.OPEN, smallerAllowance);

        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(5L);
        assertThat(b2.getBucket().getAvailableTokens()).isEqualTo(5L);
    }

    @Test
    void updateBucketStateCorrectlyWidensBucketCapacities() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        Bandwidth largerAllowance = BandwidthBuilder.builder()
                .capacity(10).refillGreedy(2, Duration.ofMinutes(1)).build();
        Bandwidth smallerAllowance = BandwidthBuilder.builder()
                .capacity(5).refillGreedy(2, Duration.ofMinutes(1)).build();
        BucketWithState b1 = registry.getBucket("a", BreakerState.OPEN, smallerAllowance);
        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(5L);
        // token inheritance is AS-IS. amount of available tokens should not increase.
        registry.updateBucketState("a", b1, BreakerState.PROBING, largerAllowance);
        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(5L);
        // after time has passed, available tokens should fill to capacity (asserts that capacity changed correctly)
        timeMeter.advance(Duration.ofMinutes(5));
        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(10L);
    }

    @Test
    void updateBucketStateDoesNotResetTokensWhenStateChangeResultsInIdenticalBucketCapacity() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        BucketWithState b1 = registry.getBucket("a", BreakerState.PROBING, bandwidth);
        b1.getBucket().tryConsume(2);
        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(0);
        registry.updateBucketState("a", b1, BreakerState.CLOSED, bandwidth);
        assertThat(b1.getBucket().getAvailableTokens()).isEqualTo(0);
    }

    @Test
    void updateBucketStateUpdatesCachedBucketState() {
        BucketRegistry registry = registryWith(10, Duration.ofMinutes(10));
        BucketWithState b = registry.getBucket("a", BreakerState.PROBING, bandwidth);
        registry.updateBucketState("a", b, BreakerState.CLOSED, bandwidth);
        BucketWithState newB = registry.getBucket("a", BreakerState.CLOSED, bandwidth);

        assertThat(newB.getState()).isEqualTo(BreakerState.CLOSED);
    }
}
