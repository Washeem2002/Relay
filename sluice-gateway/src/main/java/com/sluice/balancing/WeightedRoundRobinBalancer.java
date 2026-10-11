package com.sluice.balancing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WeightedRoundRobinBalancer implements LoadBalancer {

    private final Map<String, Integer> currentWeights = new HashMap<>();

    @Override
    public synchronized Optional<CompiledTarget> choose(List<CompiledTarget> candidates, String hashKey) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int totalWeight = 0;
        CompiledTarget best = null;
        int bestWeight = Integer.MIN_VALUE;
        for (CompiledTarget candidate : candidates) {
            totalWeight += candidate.weight();
            int updated = currentWeights.merge(candidate.id(), candidate.weight(), Integer::sum);
            if (updated > bestWeight) {
                bestWeight = updated;
                best = candidate;
            }
        }
        currentWeights.merge(best.id(), -totalWeight, Integer::sum);
        return Optional.of(best);
    }
}
