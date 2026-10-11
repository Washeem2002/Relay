package com.sluice.observability;

import com.sluice.balancing.TargetState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class SluiceMetrics {

    private final MeterRegistry registry;
    private final AtomicLong configVersion = new AtomicLong();

    public SluiceMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("sluice.config.version", configVersion, AtomicLong::get).register(registry);
    }

    public void requestCompleted(String route, int status, long nanos) {
        String statusLabel = String.valueOf(status);
        Counter.builder("sluice.requests").tags("route", route, "status", statusLabel).register(registry).increment();
        Timer.builder("sluice.request.duration")
                .tags("route", route, "status", statusLabel)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofNanos(nanos));
    }

    public void upstreamCompleted(String pool, String target, long nanos) {
        Timer.builder("sluice.upstream.duration")
                .tags("pool", pool, "target", target)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofNanos(nanos));
    }

    public void rateLimitRejected(String route) {
        Counter.builder("sluice.ratelimit.rejections").tag("route", route).register(registry).increment();
    }

    public void rateLimiterError() {
        Counter.builder("sluice.ratelimit.errors").register(registry).increment();
    }

    public void cacheResult(String route, String result) {
        Counter.builder("sluice.cache.requests").tags("route", route, "result", result).register(registry).increment();
    }

    public void healthCheck(String pool, String target, boolean success) {
        Counter.builder("sluice.healthcheck")
                .tags("pool", pool, "target", target, "result", success ? "success" : "failure")
                .register(registry)
                .increment();
    }

    public void canaryRollback(String route) {
        Counter.builder("sluice.canary.rollbacks").tag("route", route).register(registry).increment();
    }

    public void configReloadFailed() {
        Counter.builder("sluice.config.reload.failures").register(registry).increment();
    }

    public void configVersion(long version) {
        configVersion.set(version);
    }

    public void registerTarget(TargetState state) {
        Tags tags = Tags.of("pool", state.poolName(), "target", state.targetId());
        Gauge.builder("sluice.circuit.state", state, s -> s.circuit().state().metricValue())
                .tags(tags)
                .register(registry);
        Gauge.builder("sluice.target.active.connections", state, TargetState::activeConnections)
                .tags(tags)
                .register(registry);
        Gauge.builder("sluice.target.healthy", state, s -> s.isHealthEligible() ? 1 : 0)
                .tags(tags)
                .register(registry);
    }
}
