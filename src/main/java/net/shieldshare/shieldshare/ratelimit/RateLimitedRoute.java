package net.shieldshare.shieldshare.ratelimit;

public enum RateLimitedRoute {
    CREATE,
    VALIDATE,
    FETCH,
    NONE;

    public boolean isLookup() {
        return this != CREATE;
    }
}
