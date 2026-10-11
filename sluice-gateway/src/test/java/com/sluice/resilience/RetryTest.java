package com.sluice.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.RetryConfig;
import com.sluice.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryTest {

    @Test
    void retriesIdempotentMethodsOnly() {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(3, false, 0, 0, 0));
        assertThat(policy.shouldRetry(1, "GET", false)).isTrue();
        assertThat(policy.shouldRetry(1, "PUT", false)).isTrue();
        assertThat(policy.shouldRetry(1, "POST", false)).isFalse();
        assertThat(policy.shouldRetry(1, "PATCH", false)).isFalse();
    }

    @Test
    void nonIdempotentRetriesRequireExplicitOptIn() {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(3, true, 0, 0, 0));
        assertThat(policy.shouldRetry(1, "POST", false)).isTrue();
    }

    @Test
    void neverRetriesAfterBodyWasConsumedOrAttemptsExhausted() {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(2, true, 0, 0, 0));
        assertThat(policy.shouldRetry(1, "GET", true)).isFalse();
        assertThat(policy.shouldRetry(2, "GET", false)).isFalse();
        assertThat(policy.maxAttempts()).isEqualTo(2);
    }

    @Test
    void retryableStatusesAreGatewayErrors() {
        RetryPolicy policy = new RetryPolicy(RetryConfig.defaults());
        assertThat(policy.isRetryableStatus(502)).isTrue();
        assertThat(policy.isRetryableStatus(503)).isTrue();
        assertThat(policy.isRetryableStatus(504)).isTrue();
        assertThat(policy.isRetryableStatus(500)).isFalse();
        assertThat(policy.isRetryableStatus(200)).isFalse();
    }

    @Test
    void budgetCapsRetriesRelativeToTraffic() {
        MutableClock clock = new MutableClock();
        RetryBudget budget = new RetryBudget(new RetryConfig(2, false, 10, 2, 10), clock);
        assertThat(budget.tryAcquireRetry()).isTrue();
        assertThat(budget.tryAcquireRetry()).isTrue();
        assertThat(budget.tryAcquireRetry()).isFalse();
        for (int i = 0; i < 200; i++) {
            budget.recordRequest();
        }
        int granted = 0;
        for (int i = 0; i < 100; i++) {
            if (budget.tryAcquireRetry()) {
                granted++;
            }
        }
        assertThat(granted).isBetween(15, 25);
    }

    @Test
    void budgetRecoversWhenWindowRolls() {
        MutableClock clock = new MutableClock();
        RetryBudget budget = new RetryBudget(new RetryConfig(2, false, 10, 1, 10), clock);
        assertThat(budget.tryAcquireRetry()).isTrue();
        assertThat(budget.tryAcquireRetry()).isFalse();
        clock.advance(Duration.ofSeconds(11));
        assertThat(budget.tryAcquireRetry()).isTrue();
        assertThat(budget.config().budgetPercent()).isEqualTo(10);
    }
}
