package com.sluice.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BulkheadTest {

    @Test
    void rejectsWhenConcurrencyLimitReached() {
        Bulkhead bulkhead = new Bulkhead(2, 5);
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.inFlight()).isEqualTo(2);
        assertThat(bulkhead.tryAcquire()).isFalse();
        bulkhead.release();
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.maxConcurrency()).isEqualTo(2);
    }

    @Test
    void interruptedWaiterIsRejectedAndKeepsInterruptFlag() {
        Bulkhead bulkhead = new Bulkhead(1, 1_000);
        bulkhead.tryAcquire();
        Thread.currentThread().interrupt();
        assertThat(bulkhead.tryAcquire()).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
