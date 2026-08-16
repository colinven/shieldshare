package net.shieldshare.shieldshare.ratelimit.support;

import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/**
 * A mutable clock that {@code LookupCircuitBreaker} and {@code BucketRegistry} can use for testing in order to
 * move hours or minutes forward instantly.
 */
public class MutableTimeMeter implements TimeMeter {

    private long nanos;

    public void advance(Duration duration) {
        nanos += duration.toNanos();
    }
    @Override
    public long currentTimeNanos() {
        return nanos;
    }
    @Override
    public boolean isWallClockBased() {
        return false;
    }
}
