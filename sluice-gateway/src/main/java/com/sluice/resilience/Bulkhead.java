package com.sluice.resilience;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class Bulkhead {

    private final int maxConcurrency;
    private final long queueTimeoutMillis;
    private final Semaphore permits;

    public Bulkhead(int maxConcurrency, long queueTimeoutMillis) {
        this.maxConcurrency = maxConcurrency;
        this.queueTimeoutMillis = queueTimeoutMillis;
        this.permits = new Semaphore(maxConcurrency);
    }

    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }

    public int inFlight() {
        return maxConcurrency - permits.availablePermits();
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }
}
