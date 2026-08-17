package net.shieldshare.shieldshare.ratelimit;

import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.ratelimit.support.MutableTimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class LookupCircuitBreakerTest {

    private static final long MISS_CAP = 10;
    private static final long PROBING_CAP = 5;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final MutableTimeMeter timeMeter = new MutableTimeMeter();

    private LookupCircuitBreaker breakerWith(long missCapacity, long probingCapacity) {
        return new LookupCircuitBreaker(
                new AppProperties.Breaker(missCapacity, REFILL_PERIOD, COOLDOWN, probingCapacity),
                timeMeter
        );
    }

    private void recordMisses(LookupCircuitBreaker breaker, long nMisses) {
        for (int i = 0; i < nMisses + 1; i++) {
            breaker.recordMiss();
        }
    }

    @Test
    void stateChangesToOpenWhenStateIsClosedAndMissCapacityIsExceeded() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        assertThat(breaker.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void stateChangesToProbingWhenStateIsOpenAndCooldownHasElapsed() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        assertThat(breaker.state()).isEqualTo(BreakerState.OPEN);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.PROBING);
    }

    @Test
    void stateChangesToOpenWhenStateIsProbingAndProbingCapacityIsExceeded() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        assertThat(breaker.state()).isEqualTo(BreakerState.OPEN);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.PROBING);
        recordMisses(breaker, PROBING_CAP);
        assertThat(breaker.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void stateChangesToClosedWhenStateIsProbingAndCooldownHasElapsed() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.PROBING);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    void stateChangesDirectlyToClosedWhenStateIsOpenAnd2xCooldownPeriodHasElapsed() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        timeMeter.advance(COOLDOWN);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    void restoresTheFullMissBudgetWhenStateSwitchesFromProbingToClosed() {
        LookupCircuitBreaker breaker = breakerWith(MISS_CAP, PROBING_CAP);
        recordMisses(breaker, MISS_CAP);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.PROBING);
        timeMeter.advance(COOLDOWN);
        assertThat(breaker.state()).isEqualTo(BreakerState.CLOSED);
        recordMisses(breaker, PROBING_CAP);
        assertThat(breaker.state()).isEqualTo(BreakerState.CLOSED);
    }
}
