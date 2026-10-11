package com.sluice.schema;

public record CircuitBreakerConfig(
        int failureRatePercent,
        int windowSeconds,
        int minRequests,
        int cooldownMs,
        int halfOpenMaxProbes) {

    public CircuitBreakerConfig {
        if (failureRatePercent == 0) {
            failureRatePercent = 50;
        }
        if (windowSeconds == 0) {
            windowSeconds = 30;
        }
        if (minRequests == 0) {
            minRequests = 10;
        }
        if (cooldownMs == 0) {
            cooldownMs = 10_000;
        }
        if (halfOpenMaxProbes == 0) {
            halfOpenMaxProbes = 2;
        }
    }

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(0, 0, 0, 0, 0);
    }
}
