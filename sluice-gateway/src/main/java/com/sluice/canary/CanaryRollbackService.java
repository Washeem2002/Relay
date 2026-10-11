package com.sluice.canary;

import com.sluice.observability.SluiceMetrics;
import com.sluice.resilience.RollingCounter;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotHolder;
import com.sluice.schema.AutoRollbackConfig;
import com.sluice.schema.CanaryConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CanaryRollbackService {

    private static final Logger LOG = LoggerFactory.getLogger(CanaryRollbackService.class);

    private final SnapshotHolder snapshots;
    private final CanaryMonitor monitor;
    private final CanaryWeightUpdater updater;
    private final SluiceMetrics metrics;

    public CanaryRollbackService(SnapshotHolder snapshots, CanaryMonitor monitor, CanaryWeightUpdater updater,
                                 SluiceMetrics metrics) {
        this.snapshots = snapshots;
        this.monitor = monitor;
        this.updater = updater;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${sluice.gateway.canary-eval-interval-ms:5000}")
    public void evaluateCurrentSnapshot() {
        try {
            evaluate(snapshots.current());
        } catch (RuntimeException e) {
            LOG.warn("canary evaluation failed: {}", e.toString());
        }
    }

    public List<String> evaluate(RoutingSnapshot snapshot) {
        List<String> rolledBack = new ArrayList<>();
        for (CompiledRoute route : snapshot.routes().values()) {
            CanaryConfig canary = route.config().canary();
            if (canary == null || canary.weight() <= 0 || canary.autoRollback() == null) {
                continue;
            }
            AutoRollbackConfig policy = canary.autoRollback();
            RollingCounter.Counts counts = monitor.counts(route.name(), policy.windowSeconds());
            if (counts.total() >= policy.minRequests() && counts.flaggedPercent() >= policy.errorRatePercent()) {
                String reason = "canary error rate " + counts.flaggedPercent() + "% over " + counts.total()
                        + " requests reached threshold " + policy.errorRatePercent() + "%";
                if (updater.rollbackCanary(route.name(), reason)) {
                    metrics.canaryRollback(route.name());
                    LOG.warn("canary rolled back for route {}: {}", route.name(), reason);
                    rolledBack.add(route.name());
                }
                monitor.reset(route.name());
            }
        }
        return rolledBack;
    }
}
