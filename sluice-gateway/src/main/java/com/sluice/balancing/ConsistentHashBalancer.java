package com.sluice.balancing;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ConsistentHashBalancer implements LoadBalancer {

    public static final int VIRTUAL_NODES_PER_WEIGHT_UNIT = 150;

    private final long[] points;
    private final CompiledTarget[] owners;

    public ConsistentHashBalancer(List<CompiledTarget> allTargets) {
        int total = allTargets.stream().mapToInt(target -> target.weight() * VIRTUAL_NODES_PER_WEIGHT_UNIT).sum();
        long[] rawPoints = new long[total];
        CompiledTarget[] rawOwners = new CompiledTarget[total];
        Integer[] order = new Integer[total];
        int index = 0;
        for (CompiledTarget target : allTargets) {
            int nodes = target.weight() * VIRTUAL_NODES_PER_WEIGHT_UNIT;
            for (int i = 0; i < nodes; i++) {
                rawPoints[index] = Hashing.hash64(target.id() + "#" + i);
                rawOwners[index] = target;
                order[index] = index;
                index++;
            }
        }
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(rawPoints[a], rawPoints[b]));
        this.points = new long[total];
        this.owners = new CompiledTarget[total];
        for (int i = 0; i < total; i++) {
            points[i] = rawPoints[order[i]];
            owners[i] = rawOwners[order[i]];
        }
    }

    @Override
    public Optional<CompiledTarget> choose(List<CompiledTarget> candidates, String hashKey) {
        if (candidates.isEmpty() || points.length == 0) {
            return Optional.empty();
        }
        Set<String> candidateIds = new HashSet<>();
        candidates.forEach(candidate -> candidateIds.add(candidate.id()));
        int start = firstIndexAtOrAfter(Hashing.hash64(hashKey));
        for (int offset = 0; offset < points.length; offset++) {
            CompiledTarget owner = owners[(start + offset) % points.length];
            if (candidateIds.contains(owner.id())) {
                return candidates.stream().filter(candidate -> candidate.id().equals(owner.id())).findFirst();
            }
        }
        return Optional.empty();
    }

    private int firstIndexAtOrAfter(long hash) {
        int low = 0;
        int high = points.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Long.compareUnsigned(points[mid], hash) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low == points.length ? 0 : low;
    }
}
