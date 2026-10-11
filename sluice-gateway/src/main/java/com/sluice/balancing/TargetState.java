package com.sluice.balancing;

import com.sluice.resilience.CircuitBreaker;
import com.sluice.schema.CircuitBreakerConfig;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

public final class TargetState {

    private final String poolName;
    private final String targetId;
    private final Clock clock;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger passiveFailures = new AtomicInteger();
    private final AtomicInteger probeFailures = new AtomicInteger();
    private volatile boolean ejected;
    private volatile long ejectedUntilMillis;
    private volatile CircuitBreaker circuit;

    public TargetState(String poolName, String targetId, CircuitBreakerConfig circuitConfig, Clock clock) {
        this.poolName = poolName;
        this.targetId = targetId;
        this.clock = clock;
        this.circuit = new CircuitBreaker(circuitConfig, clock);
    }

    public String poolName() {
        return poolName;
    }

    public String targetId() {
        return targetId;
    }

    public CircuitBreaker circuit() {
        return circuit;
    }

    public void configureCircuit(CircuitBreakerConfig config) {
        if (!circuit.config().equals(config)) {
            circuit = new CircuitBreaker(config, clock);
        }
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public boolean isSelectable() {
        return (!ejected || clock.millis() >= ejectedUntilMillis) && circuit.isCallPermitted();
    }

    public boolean isHealthEligible() {
        return !ejected || clock.millis() >= ejectedUntilMillis;
    }

    public HealthStatus healthStatus() {
        if (!ejected) {
            return HealthStatus.HEALTHY;
        }
        return clock.millis() >= ejectedUntilMillis ? HealthStatus.PROBATION : HealthStatus.EJECTED;
    }

    public int consecutiveFailures() {
        return Math.max(passiveFailures.get(), probeFailures.get());
    }

    public void recordPassiveSuccess() {
        passiveFailures.set(0);
        if (ejected && clock.millis() >= ejectedUntilMillis) {
            ejected = false;
            probeFailures.set(0);
        }
    }

    public void recordPassiveFailure(int ejectAfterFailures, long ejectDurationMillis) {
        if (passiveFailures.incrementAndGet() >= ejectAfterFailures) {
            eject(ejectDurationMillis);
        }
    }

    public void recordProbeSuccess() {
        passiveFailures.set(0);
        probeFailures.set(0);
        ejected = false;
    }

    public void recordProbeFailure(int ejectAfterFailures, long ejectDurationMillis) {
        if (probeFailures.incrementAndGet() >= ejectAfterFailures) {
            eject(ejectDurationMillis);
        }
    }

    public void eject(long durationMillis) {
        ejectedUntilMillis = clock.millis() + durationMillis;
        ejected = true;
    }
}
