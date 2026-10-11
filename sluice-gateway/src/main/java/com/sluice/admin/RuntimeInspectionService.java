package com.sluice.admin;

import com.sluice.balancing.CompiledTarget;
import com.sluice.balancing.HealthStatus;
import com.sluice.resilience.CircuitSnapshot;
import com.sluice.routing.CompiledPool;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotHolder;
import com.sluice.schema.ConfigDocument;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RuntimeInspectionService {

    public record TargetView(
            String pool,
            String target,
            String url,
            int weight,
            HealthStatus health,
            int consecutiveFailures,
            int activeConnections,
            CircuitSnapshot circuit) {
    }

    public record PoolView(String name, String algorithm, int inFlight, int maxConcurrency, List<TargetView> targets) {
    }

    public record SnapshotDump(long version, Instant compiledAt, List<String> routeNames, List<PoolView> pools,
                               ConfigDocument document) {
    }

    private final SnapshotHolder holder;

    public RuntimeInspectionService(SnapshotHolder holder) {
        this.holder = holder;
    }

    public SnapshotDump snapshot() {
        RoutingSnapshot snapshot = holder.current();
        List<PoolView> pools = snapshot.pools().values().stream()
                .sorted(Comparator.comparing(CompiledPool::name))
                .map(this::poolView)
                .toList();
        List<String> routes = snapshot.routes().keySet().stream().sorted().toList();
        return new SnapshotDump(snapshot.version(), snapshot.compiledAt(), routes, pools, snapshot.document());
    }

    public List<TargetView> circuits() {
        return snapshot().pools().stream().flatMap(pool -> pool.targets().stream()).toList();
    }

    public TargetView resetCircuit(String poolName, String targetId) {
        CompiledTarget target = find(poolName, targetId);
        target.state().circuit().reset();
        return view(poolName, target);
    }

    public TargetView target(String poolName, String targetId) {
        return view(poolName, find(poolName, targetId));
    }

    private CompiledTarget find(String poolName, String targetId) {
        CompiledPool pool = holder.current().pools().get(poolName);
        if (pool == null) {
            throw new NotFoundException("pool " + poolName + " not found in the active snapshot");
        }
        return pool.targets().stream()
                .filter(target -> target.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("target " + targetId + " not found in pool " + poolName));
    }

    private PoolView poolView(CompiledPool pool) {
        List<TargetView> targets = pool.targets().stream().map(target -> view(pool.name(), target)).toList();
        return new PoolView(pool.name(), pool.config().algorithm().name(), pool.runtime().bulkhead().inFlight(),
                pool.runtime().bulkhead().maxConcurrency(), targets);
    }

    private TargetView view(String poolName, CompiledTarget target) {
        return new TargetView(poolName, target.id(), target.config().url(), target.weight(),
                target.state().healthStatus(), target.state().consecutiveFailures(),
                target.state().activeConnections(), target.state().circuit().snapshot());
    }
}
