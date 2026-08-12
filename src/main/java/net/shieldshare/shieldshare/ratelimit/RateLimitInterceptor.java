package net.shieldshare.shieldshare.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.shieldshare.shieldshare.config.AppProperties;
import net.shieldshare.shieldshare.exception.ErrorResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static String REJECTED_MESSAGE = "Rate Limit Exceeded";

    private final AppProperties.SizeCaps sizeCaps;
    private final BucketRegistry registry;
    private final ClientIpResolver ipResolver;
    private final LookupCircuitBreaker breaker;
    private final RateLimitPolicy policy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        //TODO: add some sort of FilterInputStream to count incoming bytes. Content-Length can be spoofed,
        // and content length checks don't belong here anyways
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            reject(response, HttpStatus.LENGTH_REQUIRED, "Content-Length header required");
        }
        if (contentLength > sizeCaps.maxRequestBytes()) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE, "Content length exceeds 1.5MiB maximum");
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = handlerMethod.getMethod().getAnnotation(RateLimited.class);
        if (annotation == null) return true; // Not a rate limited method
        RateLimitedRoute route = annotation.route();
        if (route == RateLimitedRoute.NONE) return true;

        String clientIp = ipResolver.resolve(request);
        String reqBucketKey = cacheKeyFor(clientIp, route);
        BreakerState state = breaker.state();
        BucketWithState requestBucket = requestBucket(reqBucketKey, route, state);

        // Check if breaker state has changed since last read. If so, update bucket state and bucket configuration
        if (requestBucket.getState() != state) {
            syncBucket(reqBucketKey, requestBucket, route, state);
        }

        ConsumptionProbe requestProbe = requestBucket.getBucket().tryConsumeAndReturnRemaining(1);
        if (!requestProbe.isConsumed()) {
            reject(response, HttpStatus.TOO_MANY_REQUESTS, REJECTED_MESSAGE);
            return false;
        }

        if (route == RateLimitedRoute.CREATE) {
            ConsumptionProbe byteProbe = byteBucket(clientIp).getBucket().tryConsumeAndReturnRemaining(1);
            if (!byteProbe.isConsumed()) {
                reject(response, HttpStatus.TOO_MANY_REQUESTS, REJECTED_MESSAGE);
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        RateLimited annotation = handlerMethod.getMethod().getAnnotation(RateLimited.class);
        if (annotation == null) return;
        RateLimitedRoute route = annotation.route();
        if (route != null && route.isLookup() && response.getStatus() == HttpStatus.NOT_FOUND.value()) {
            breaker.recordMiss();
        }
    }

    /**
     * Get
     */
    private BucketWithState requestBucket(String cacheKey, RateLimitedRoute route, BreakerState state) {
        return registry.getBucket(cacheKey, state, policy.getRequestBandwidth(route, state));
    }

    private BucketWithState byteBucket(String clientIp) {
        String cacheKey = "BYTE_BUCKET|" + clientIp;
        // byte buckets are completely unaffected by breaker state, which is why we pass a null value...
        return registry.getBucket(cacheKey, null, policy.getByteBandwidth());
    }

    /**
     * Sync a buckets internal state and bucket configuration with the current state of the {@code LookupCircuitBreaker}.
     * This is the core mechanism for lazily migrating bucket bandwidths dynamically. Internally, this method will
     * call {@code b.replaceConfiguration()}, update {@code b}'s state, and put the updated bucket into the cache.
     * Use this method when {@code b.getState() != currState}.
     * @param cacheKey the key used to update the cache entry
     * @param b the bucket to sync
     * @param route the route this bucket is assigned to
     * @param currState the current state of the breaker
     */
    private void syncBucket(String cacheKey, BucketWithState b, RateLimitedRoute route, BreakerState currState) {
        registry.updateBucketState(cacheKey, b, currState, policy.getRequestBandwidth(route, currState));
    }

    /**
     * Generate a composite cache key for this request from the client ip and route.
     */
    private String cacheKeyFor(String clientIp, RateLimitedRoute route) {
        return route.name() + "|" + clientIp;
    }

    /**
     * Write an {@code ErrorResponse} object with the provided {@code status} and {@code message} to the {@code response}
     * output stream.
     * @throws IOException when unable to write
     */
    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setHeader("Content-Type", "application/json");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                LocalDateTime.now(), status.value(), status.getReasonPhrase(), message));
    }
}
