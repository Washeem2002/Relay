package com.sluice.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RequestCoalescerTest {

    private static CacheEntry entry() {
        return new CacheEntry(200, Map.of(), new byte[] {1}, 0, 1, 2);
    }

    @Test
    void onlyOneLeaderPerKeyAndFollowersReceiveItsResult() throws Exception {
        RequestCoalescer coalescer = new RequestCoalescer();
        int callers = 50;
        AtomicInteger leaders = new AtomicInteger();
        AtomicInteger upstreamCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicInteger served = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        RequestCoalescer.Ticket ticket = coalescer.join("k");
                        if (ticket.leader()) {
                            leaders.incrementAndGet();
                            upstreamCalls.incrementAndGet();
                            Thread.sleep(200);
                            coalescer.complete("k", ticket, Optional.of(entry()));
                            served.incrementAndGet();
                        } else if (coalescer.await(ticket, 5_000).isPresent()) {
                            served.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(leaders.get()).isEqualTo(1);
        assertThat(served.get()).isEqualTo(callers);
        assertThat(coalescer.inFlightCount()).isZero();
    }

    @Test
    void differentKeysHaveIndependentLeaders() {
        RequestCoalescer coalescer = new RequestCoalescer();
        assertThat(coalescer.join("a").leader()).isTrue();
        assertThat(coalescer.join("b").leader()).isTrue();
        assertThat(coalescer.join("a").leader()).isFalse();
    }

    @Test
    void followersGetEmptyWhenLeaderReportsUncacheable() {
        RequestCoalescer coalescer = new RequestCoalescer();
        RequestCoalescer.Ticket leader = coalescer.join("k");
        RequestCoalescer.Ticket follower = coalescer.join("k");
        coalescer.complete("k", leader, Optional.empty());
        assertThat(coalescer.await(follower, 1_000)).isEmpty();
        assertThat(coalescer.join("k").leader()).isTrue();
    }

    @Test
    void awaitTimesOutWhenLeaderNeverCompletes() {
        RequestCoalescer coalescer = new RequestCoalescer();
        coalescer.join("k");
        RequestCoalescer.Ticket follower = coalescer.join("k");
        assertThat(coalescer.await(follower, 50)).isEmpty();
    }

    @Test
    void awaitReturnsEmptyWhenInterrupted() {
        RequestCoalescer coalescer = new RequestCoalescer();
        coalescer.join("k");
        RequestCoalescer.Ticket follower = coalescer.join("k");
        Thread.currentThread().interrupt();
        assertThat(coalescer.await(follower, 1_000)).isEmpty();
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void refreshIsSingleFlightPerKey() {
        RequestCoalescer coalescer = new RequestCoalescer();
        assertThat(coalescer.tryStartRefresh("k")).isTrue();
        assertThat(coalescer.tryStartRefresh("k")).isFalse();
        coalescer.finishRefresh("k");
        assertThat(coalescer.tryStartRefresh("k")).isTrue();
    }
}
