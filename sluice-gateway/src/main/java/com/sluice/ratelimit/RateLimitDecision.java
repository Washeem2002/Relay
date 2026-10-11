package com.sluice.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long resetEpochSeconds,
        long retryAfterSeconds,
        String scope) {

    public RateLimitDecision withScope(String newScope) {
        return new RateLimitDecision(allowed, limit, remaining, resetEpochSeconds, retryAfterSeconds, newScope);
    }
}
