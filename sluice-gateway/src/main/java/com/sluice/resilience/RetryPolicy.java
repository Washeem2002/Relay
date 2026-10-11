package com.sluice.resilience;

import com.sluice.schema.RetryConfig;
import java.util.Set;

public final class RetryPolicy {

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE");
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);

    private final RetryConfig config;

    public RetryPolicy(RetryConfig config) {
        this.config = config;
    }

    public int maxAttempts() {
        return config.maxAttempts();
    }

    public boolean shouldRetry(int attemptsMade, String method, boolean bodyConsumed) {
        if (attemptsMade >= config.maxAttempts()) {
            return false;
        }
        if (bodyConsumed) {
            return false;
        }
        return config.retryNonIdempotent() || IDEMPOTENT_METHODS.contains(method);
    }

    public boolean isRetryableStatus(int status) {
        return RETRYABLE_STATUSES.contains(status);
    }
}
