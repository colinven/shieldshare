package net.shieldshare.shieldshare.ratelimit;

import io.github.bucket4j.Bucket;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BucketWithState {
    private final Bucket bucket;
    private volatile BreakerState state;
}
