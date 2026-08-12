package net.shieldshare.shieldshare.ratelimit;

public enum RateLimitedRoute {
    CREATE,
    VALIDATE,
    FETCH,
    NONE; // Sentinel value to assign to @RateLimited's default value

    public boolean isLookup() {
        return this != CREATE;
    }
}
