package net.shieldshare.shieldshare.ratelimit;

import net.shieldshare.shieldshare.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

public class RateLimitPolicyTest {

    private static final AppProperties.Limit CREATE_REQUESTS =
            new AppProperties.Limit(20, 10, Duration.ofMinutes(1));
    private static final AppProperties.Limit CREATE_BYTES =
            new AppProperties.Limit(4_194_304, 20_971_520, Duration.ofHours(1));
    private static final AppProperties.Limit FETCH_CLOSED =
            new AppProperties.Limit(20, 10, Duration.ofMinutes(1));
    private static final AppProperties.Limit FETCH_OPEN =
            new AppProperties.Limit(3, 3, Duration.ofMinutes(1));
    private static final AppProperties.Limit VALIDATE_CLOSED =
            new AppProperties.Limit(60, 30, Duration.ofMinutes(1));
    private static final AppProperties.Limit VALIDATE_OPEN =
            new AppProperties.Limit(5, 5, Duration.ofMinutes(1));

    private final RateLimitPolicy policy = new RateLimitPolicy(new AppProperties.RateLimit(
            new AppProperties.CreateLimits(CREATE_REQUESTS, CREATE_BYTES),
            new AppProperties.LookupLimits(FETCH_CLOSED, FETCH_OPEN),
            new AppProperties.LookupLimits(VALIDATE_CLOSED, VALIDATE_OPEN),
            new AppProperties.Breaker(300, Duration.ofMinutes(1),
                    Duration.ofMinutes(5), 30),
            new AppProperties.BucketCache(100_000, Duration.ofMinutes(10))
    ));

    @Test
    void usesLooserBudgetsWhenBreakerIsInClosedState() {
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.VALIDATE, BreakerState.CLOSED).getCapacity())
                .isEqualTo(VALIDATE_CLOSED.capacity());
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.FETCH, BreakerState.CLOSED).getCapacity())
                .isEqualTo(FETCH_CLOSED.capacity());
    }

    @Test
    void tightensBudgetsWhenBreakerIsInOpenState() {
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.FETCH, BreakerState.OPEN).getCapacity())
                .isEqualTo(FETCH_OPEN.capacity());
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.VALIDATE, BreakerState.OPEN).getCapacity())
                .isEqualTo(VALIDATE_OPEN.capacity());
    }

    @Test
    void returnsClosedBudgetsWhenBreakerIsInProbingState() {
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.VALIDATE, BreakerState.PROBING).getCapacity())
                .isEqualTo(VALIDATE_CLOSED.capacity());
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.FETCH, BreakerState.PROBING).getCapacity())
                .isEqualTo(FETCH_CLOSED.capacity());
    }

    @Test
    void leavesCreateRouteBudgetsUntouchedRegardlessOfBreakerState() {
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.CREATE, BreakerState.OPEN).getCapacity())
                .isEqualTo(CREATE_REQUESTS.capacity());
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.CREATE, BreakerState.CLOSED).getCapacity())
                .isEqualTo(CREATE_REQUESTS.capacity());
        assertThat(policy.getRequestBandwidth(RateLimitedRoute.CREATE, BreakerState.PROBING).getCapacity())
                .isEqualTo(CREATE_REQUESTS.capacity());
    }

    @Test
    void throwsExceptionWhenBudgetsAreRequestedForDefaultRoute() {
        assertThatThrownBy(() -> policy.getRequestBandwidth(RateLimitedRoute.NONE, BreakerState.CLOSED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctlyReturnsByteBandwidth() {
        assertThat(policy.getByteBandwidth().getCapacity()).isEqualTo(CREATE_BYTES.capacity());
    }
}
