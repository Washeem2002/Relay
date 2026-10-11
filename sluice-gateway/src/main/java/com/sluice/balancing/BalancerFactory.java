package com.sluice.balancing;

import com.sluice.schema.PoolConfig;
import java.util.List;

public final class BalancerFactory {

    private BalancerFactory() {
    }

    public static LoadBalancer create(PoolConfig pool, List<CompiledTarget> targets) {
        return switch (pool.algorithm()) {
            case WEIGHTED_ROUND_ROBIN -> new WeightedRoundRobinBalancer();
            case LEAST_CONNECTIONS -> new LeastConnectionsBalancer();
            case CONSISTENT_HASH -> new ConsistentHashBalancer(targets);
        };
    }
}
