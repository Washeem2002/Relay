package com.sluice.balancing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TargetStateTest {

    @Test
    void ejectsAfterConsecutiveFailuresAndReturnsAfterRecovery() {
        MutableClock clock = new MutableClock();
        TargetState state = new TargetState("p", "t", CircuitBreakerConfig.defaults(), clock);
        state.recordPassiveFailure(3, 10_000);
        state.recordPassiveFailure(3, 10_000);
        assertThat(state.isSelectable()).isTrue();
        state.recordPassiveFailure(3, 10_000);
        assertThat(state.isSelectable()).isFalse();
        assertThat(state.healthStatus()).isEqualTo(HealthStatus.EJECTED);
        clock.advance(Duration.ofSeconds(10));
        assertThat(state.healthStatus()).isEqualTo(HealthStatus.PROBATION);
        assertThat(state.isSelectable()).isTrue();
        state.recordPassiveSuccess();
        assertThat(state.healthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(state.consecutiveFailures()).isZero();
    }

    @Test
    void probeFailuresAreNotResetByPassiveSuccesses() {
        TargetState state = new TargetState("p", "t", CircuitBreakerConfig.defaults(), new MutableClock());
        state.recordProbeFailure(2, 60_000);
        state.recordPassiveSuccess();
        state.recordProbeFailure(2, 60_000);
        assertThat(state.isSelectable()).isFalse();
        assertThat(state.consecutiveFailures()).isEqualTo(2);
        state.recordPassiveSuccess();
        assertThat(state.healthStatus()).isEqualTo(HealthStatus.EJECTED);
        state.recordProbeSuccess();
        assertThat(state.healthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(state.consecutiveFailures()).isZero();
    }

    @Test
    void failedProbationRequestReEjects() {
        MutableClock clock = new MutableClock();
        TargetState state = new TargetState("p", "t", CircuitBreakerConfig.defaults(), clock);
        state.eject(5_000);
        clock.advance(Duration.ofSeconds(5));
        assertThat(state.isHealthEligible()).isTrue();
        state.recordPassiveFailure(1, 5_000);
        assertThat(state.isSelectable()).isFalse();
    }

    @Test
    void successResetsFailureStreak() {
        TargetState state = new TargetState("p", "t", CircuitBreakerConfig.defaults(), new MutableClock());
        state.recordPassiveFailure(3, 1_000);
        state.recordPassiveFailure(3, 1_000);
        state.recordPassiveSuccess();
        state.recordPassiveFailure(3, 1_000);
        assertThat(state.isSelectable()).isTrue();
    }

    @Test
    void openCircuitMakesTargetUnselectable() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = new CircuitBreakerConfig(50, 10, 2, 5_000, 1);
        TargetState state = new TargetState("p", "t", config, clock);
        state.circuit().tryAcquire();
        state.circuit().recordFailure();
        state.circuit().tryAcquire();
        state.circuit().recordFailure();
        assertThat(state.isSelectable()).isFalse();
        assertThat(state.isHealthEligible()).isTrue();
        state.configureCircuit(new CircuitBreakerConfig(50, 10, 2, 5_000, 1));
        assertThat(state.isSelectable()).isFalse();
        state.configureCircuit(new CircuitBreakerConfig(60, 10, 2, 5_000, 1));
        assertThat(state.isSelectable()).isTrue();
        assertThat(state.poolName()).isEqualTo("p");
        assertThat(state.targetId()).isEqualTo("t");
    }
}
