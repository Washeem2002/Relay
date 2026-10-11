package com.sluice.balancing;

import java.util.List;
import java.util.Optional;

public final class LeastConnectionsBalancer implements LoadBalancer {

    @Override
    public Optional<CompiledTarget> choose(List<CompiledTarget> candidates, String hashKey) {
        CompiledTarget best = null;
        double bestLoad = Double.MAX_VALUE;
        for (CompiledTarget candidate : candidates) {
            double load = (double) candidate.state().activeConnections() / candidate.weight();
            if (load < bestLoad) {
                bestLoad = load;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}
