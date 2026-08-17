package net.shieldshare.shieldshare.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import lombok.extern.slf4j.Slf4j;
import net.shieldshare.shieldshare.config.AppProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LookupCircuitBreaker {

    private final AppProperties.Breaker config;
    private final TimeMeter timeMeter;

    private volatile BreakerState state = BreakerState.CLOSED;
    private volatile long stateEnteredNanos;
    private volatile Bucket missBucket;

    LookupCircuitBreaker(AppProperties.Breaker config, TimeMeter timeMeter) {
        this.config = config;
        this.timeMeter = timeMeter;
        this.stateEnteredNanos = timeMeter.currentTimeNanos();
        this.missBucket = missBucketOf(config.missCapacity());
    }

    public LookupCircuitBreaker(AppProperties appProperties, TimeMeter timeMeter) {
        this(appProperties.rateLimit().breaker(), timeMeter);
    }

    /**
     * Called once per lookup that did not resolve to a secret. Exhausting the miss budget trips the breaker.
     */
    public void recordMiss() {
        if (state == BreakerState.OPEN) {
            return;
        }
        if (missBucket.tryConsume(1)) {
            return;
        }
        synchronized (this) {
            if (state == BreakerState.OPEN) {
                return;
            }
            log.warn("Lookup circuit breaker tripped: miss budget exhausted from state {}. Lookup budgets tightened for {} minutes.",
                    state, config.cooldown().toMinutes());
            transitionTo(BreakerState.OPEN, config.probingCapacity());
        }
    }

    /**
     * Get the current breaker state. Reading the state can trigger a transition since the {@code OPEN -> PROBING}
     * and {@code PROBING -> CLOSED} transitions are driven by elapsed time.
     */
    public BreakerState state() {
        BreakerState current = state;
        if (current == BreakerState.CLOSED || cooldownHasNotEnded()) {
            return current;
        }
        synchronized (this) {
            if (state == BreakerState.CLOSED || cooldownHasNotEnded()) {
                return state;
            }
            if (state == BreakerState.OPEN){
                /*
                doubleCooldownHaElapsed() check reasoning:
                If breaker was flipped to OPEN, and 2x the cooldown period has passed, state should be
                set directly to CLOSED (enough time passed for both the OPEN *and* PROBING cooldown periods to end).
                Without this, if the breaker is flipped to OPEN, the next request will read state=PROBING regardless of
                if enough time has passed for state to have returned back to CLOSED. (state does not change on its own,
                but rather only by invoking this method).
                 */
                if (doubleCooldownHasElapsed()) {
                    transitionTo(BreakerState.CLOSED, config.missCapacity());
                } else {
                    transitionTo(BreakerState.PROBING, config.probingCapacity());
                }
            } else {
                transitionTo(BreakerState.CLOSED, config.missCapacity());
            }
            return state;
        }
    }

    private void transitionTo(BreakerState newState, long missCapacity) {
        state = newState;
        stateEnteredNanos = timeMeter.currentTimeNanos();
        missBucket = missBucketOf(missCapacity);
        log.info("Lookup circuit breaker transitioned to state {}", state);
    }

    private boolean cooldownHasNotEnded() {
        return timeMeter.currentTimeNanos() - stateEnteredNanos < config.cooldown().toNanos();
    }

    private boolean doubleCooldownHasElapsed() {
        return timeMeter.currentTimeNanos() - stateEnteredNanos >= config.cooldown().toNanos() * 2;
    }

    private Bucket missBucketOf(long capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, config.missRefillPeriod())
                        .build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
