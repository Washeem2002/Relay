package com.sluice.resilience;

import com.sluice.schema.RetryConfig;
import java.time.Clock;

public final class RetryBudget {

    private static final int BUCKETS = 10;

    private final RetryConfig config;
    private final RollingCounter window;

    public RetryBudget(RetryConfig config, Clock clock) {
        this.config = config;
        this.window = new RollingCounter(clock, config.budgetWindowSeconds() * 1000L, BUCKETS);
    }

    public RetryConfig config() {
        return config;
    }

    public synchronized void recordRequest() {
        window.record(false);
    }

    public synchronized boolean tryAcquireRetry() {
        RollingCounter.Counts counts = window.counts();
        long allowed = Math.max(config.budgetMinRetries(), counts.total() * config.budgetPercent() / 100);
        if (counts.flagged() + 1 > allowed) {
            return false;
        }
        window.record(true);
        return true;
    }
}
