package com.sluice.schema;

public record RetryConfig(
        int maxAttempts,
        boolean retryNonIdempotent,
        int budgetPercent,
        int budgetMinRetries,
        int budgetWindowSeconds) {

    public RetryConfig {
        if (maxAttempts == 0) {
            maxAttempts = 2;
        }
        if (budgetPercent == 0) {
            budgetPercent = 20;
        }
        if (budgetMinRetries == 0) {
            budgetMinRetries = 10;
        }
        if (budgetWindowSeconds == 0) {
            budgetWindowSeconds = 10;
        }
    }

    public static RetryConfig defaults() {
        return new RetryConfig(0, false, 0, 0, 0);
    }
}
