package com.sluice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sluice.config.SluiceProperties;
import com.sluice.observability.SluiceMetrics;
import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.support.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RateLimitServiceTest {

    private static final RateLimitPolicy POLICY = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 10, 60, 0);

    private static SluiceProperties withFailMode(SluiceProperties.FailMode mode) {
        SluiceProperties base = TestProps.create();
        return new SluiceProperties(base.admin(), base.security(), new SluiceProperties.RateLimit(mode, "p:"),
                base.config(), base.gateway(), base.cache());
    }

    private static RateLimitDecision decision(boolean allowed, long remaining, String scope) {
        return new RateLimitDecision(allowed, 10, remaining, 100, allowed ? 0 : 5, scope);
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check(anyString(), any(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitService service = new RateLimitService(limiter, withFailMode(SluiceProperties.FailMode.OPEN),
                new SluiceMetrics(registry));
        RateLimitService.Outcome outcome = service.evaluate("r", "t", null, POLICY, null);
        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.limiterUnavailable()).isTrue();
        assertThat(outcome.decision()).isNull();
        assertThat(registry.get("sluice.ratelimit.errors").counter().count()).isEqualTo(1.0);
    }

    @Test
    void failsClosedWhenConfiguredTo() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check(anyString(), any(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
        RateLimitService service = new RateLimitService(limiter, withFailMode(SluiceProperties.FailMode.CLOSED),
                new SluiceMetrics(new SimpleMeterRegistry()));
        RateLimitService.Outcome outcome = service.evaluate("r", "t", POLICY, null, null);
        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.limiterUnavailable()).isTrue();
    }

    @Test
    void stopsAtTheFirstDenyingScopeAndReportsIt() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check("p:global", POLICY, "global")).thenReturn(decision(true, 9, "global"));
        when(limiter.check("p:tenant:t", POLICY, "tenant")).thenReturn(decision(false, 0, "tenant"));
        RateLimitService service = new RateLimitService(limiter, withFailMode(SluiceProperties.FailMode.OPEN),
                new SluiceMetrics(new SimpleMeterRegistry()));
        RateLimitService.Outcome outcome = service.evaluate("r", "t", POLICY, POLICY, POLICY);
        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.decision().scope()).isEqualTo("tenant");
        verify(limiter, never()).check("p:route:r:t", POLICY, "tenant-route");
    }

    @Test
    void reportsTheTightestRemainingQuotaWhenEverythingPasses() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check("p:global", POLICY, "global")).thenReturn(decision(true, 900, "global"));
        when(limiter.check("p:tenant:t", POLICY, "tenant")).thenReturn(decision(true, 3, "tenant"));
        when(limiter.check("p:route:r:t", POLICY, "tenant-route")).thenReturn(decision(true, 7, "tenant-route"));
        RateLimitService service = new RateLimitService(limiter, withFailMode(SluiceProperties.FailMode.OPEN),
                new SluiceMetrics(new SimpleMeterRegistry()));
        RateLimitService.Outcome outcome = service.evaluate("r", "t", POLICY, POLICY, POLICY);
        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.decision().remaining()).isEqualTo(3);
        assertThat(outcome.decision().scope()).isEqualTo("tenant");
        assertThat(outcome.decision().withScope("x").scope()).isEqualTo("x");
    }

    @Test
    void noPoliciesMeansNoDecision() {
        RateLimiter limiter = mock(RateLimiter.class);
        RateLimitService service = new RateLimitService(limiter, withFailMode(SluiceProperties.FailMode.OPEN),
                new SluiceMetrics(new SimpleMeterRegistry()));
        RateLimitService.Outcome outcome = service.evaluate("r", "t", null, null, null);
        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.decision()).isNull();
        assertThat(outcome.limiterUnavailable()).isFalse();
    }
}
