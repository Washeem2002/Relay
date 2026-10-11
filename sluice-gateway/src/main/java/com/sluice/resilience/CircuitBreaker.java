package com.sluice.resilience;

import com.sluice.schema.CircuitBreakerConfig;
import java.time.Clock;

public final class CircuitBreaker {

    private static final int BUCKETS = 10;

    private final CircuitBreakerConfig config;
    private final Clock clock;
    private final RollingCounter window;
    private CircuitState state = CircuitState.CLOSED;
    private long openedAtMillis;
    private int probesInFlight;

    public CircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.window = new RollingCounter(clock, config.windowSeconds() * 1000L, BUCKETS);
    }

    public CircuitBreakerConfig config() {
        return config;
    }

    public synchronized boolean isCallPermitted() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> cooldownElapsed();
            case HALF_OPEN -> probesInFlight < config.halfOpenMaxProbes();
        };
    }

    public synchronized boolean tryAcquire() {
        if (state == CircuitState.OPEN && cooldownElapsed()) {
            state = CircuitState.HALF_OPEN;
            probesInFlight = 0;
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (probesInFlight < config.halfOpenMaxProbes()) {
                    probesInFlight++;
                    yield true;
                }
                yield false;
            }
        };
    }

    public synchronized void recordSuccess() {
        switch (state) {
            case HALF_OPEN -> close();
            case CLOSED -> window.record(false);
            case OPEN -> {
            }
        }
    }

    public synchronized void recordFailure() {
        switch (state) {
            case HALF_OPEN -> open();
            case CLOSED -> {
                window.record(true);
                RollingCounter.Counts counts = window.counts();
                if (counts.total() >= config.minRequests()
                        && counts.flaggedPercent() >= config.failureRatePercent()) {
                    open();
                }
            }
            case OPEN -> {
            }
        }
    }

    public synchronized CircuitState state() {
        return state;
    }

    public synchronized void reset() {
        close();
    }

    public synchronized CircuitSnapshot snapshot() {
        RollingCounter.Counts counts = window.counts();
        return new CircuitSnapshot(state, counts.total(), counts.flagged(), counts.flaggedPercent(),
                state == CircuitState.CLOSED ? 0 : openedAtMillis);
    }

    private boolean cooldownElapsed() {
        return clock.millis() - openedAtMillis >= config.cooldownMs();
    }

    private void open() {
        state = CircuitState.OPEN;
        openedAtMillis = clock.millis();
        probesInFlight = 0;
    }

    private void close() {
        state = CircuitState.CLOSED;
        probesInFlight = 0;
        window.reset();
    }
}
