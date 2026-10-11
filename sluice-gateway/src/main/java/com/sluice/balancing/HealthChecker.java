package com.sluice.balancing;

import com.sluice.observability.SluiceMetrics;
import com.sluice.routing.CompiledPool;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotHolder;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthChecker {

    private final SnapshotHolder holder;
    private final SluiceMetrics metrics;
    private final Clock clock;
    private final Map<String, Long> lastProbeAt = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    public HealthChecker(SnapshotHolder holder, SluiceMetrics metrics, Clock clock) {
        this.holder = holder;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sluice.gateway.health-tick-ms:500}")
    public void tick() {
        runDueProbes(holder.current());
    }

    public void runDueProbes(RoutingSnapshot snapshot) {
        long now = clock.millis();
        Set<String> live = new HashSet<>();
        for (CompiledPool pool : snapshot.pools().values()) {
            if (pool.config().healthPath() == null) {
                continue;
            }
            for (CompiledTarget target : pool.targets()) {
                String key = pool.name() + "|" + target.id();
                live.add(key);
                long last = lastProbeAt.getOrDefault(key, 0L);
                if (now - last >= pool.config().healthIntervalMs() && inFlight.add(key)) {
                    lastProbeAt.put(key, now);
                    probes.submit(() -> probe(key, pool, target));
                }
            }
        }
        lastProbeAt.keySet().retainAll(live);
    }

    private void probe(String key, CompiledPool pool, CompiledTarget target) {
        try {
            boolean healthy = pool.runtime().probe(target.uri(), pool.config().healthPath(),
                    pool.config().healthTimeoutMs());
            metrics.healthCheck(pool.name(), target.id(), healthy);
            if (healthy) {
                target.state().recordHealthSuccess();
            } else {
                target.state().recordHealthFailure(pool.config().ejectAfterFailures(), pool.config().ejectDurationMs());
            }
        } finally {
            inFlight.remove(key);
        }
    }

    @PreDestroy
    void shutdown() {
        probes.shutdownNow();
    }
}
