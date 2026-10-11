package com.sluice.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private MutableClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        breaker = new CircuitBreaker(new CircuitBreakerConfig(50, 10, 4, 5_000, 1), clock);
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
        }
    }

    @Test
    void staysClosedBelowMinimumRequests() {
        fail(3);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void opensWhenFailureRateReachesThresholdAfterMinimumRequests() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void doesNotOpenWhenSuccessesDiluteFailures() {
        for (int i = 0; i < 6; i++) {
            breaker.tryAcquire();
            breaker.recordSuccess();
        }
        fail(2);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void movesToHalfOpenAfterCooldownAndLimitsProbes() {
        fail(4);
        clock.advance(Duration.ofMillis(4_999));
        assertThat(breaker.isCallPermitted()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.isCallPermitted()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.isCallPermitted()).isFalse();
    }

    @Test
    void halfOpenSuccessClosesCircuit() {
        fail(4);
        clock.advance(Duration.ofSeconds(5));
        breaker.tryAcquire();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().windowRequests()).isZero();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void halfOpenFailureReopensAndRestartsCooldown() {
        fail(4);
        clock.advance(Duration.ofSeconds(5));
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        clock.advance(Duration.ofMillis(4_000));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofMillis(1_000));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void oldFailuresAgeOutOfTheRollingWindow() {
        fail(3);
        clock.advance(Duration.ofSeconds(11));
        fail(1);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().windowRequests()).isEqualTo(1);
    }

    @Test
    void manualResetClosesOpenCircuit() {
        fail(4);
        breaker.reset();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().openedAtEpochMillis()).isZero();
    }

    @Test
    void resultsRecordedWhileOpenAreIgnored() {
        fail(4);
        breaker.recordSuccess();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.config().cooldownMs()).isEqualTo(5_000);
        assertThat(breaker.state().metricValue()).isEqualTo(2);
    }
}
