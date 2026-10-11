package com.sluice.routing;

import com.sluice.balancing.CompiledTarget;
import com.sluice.balancing.LoadBalancer;
import com.sluice.balancing.PoolRuntime;
import com.sluice.schema.PoolConfig;
import java.util.List;

public record CompiledPool(PoolConfig config, List<CompiledTarget> targets, LoadBalancer balancer, PoolRuntime runtime) {

    public String name() {
        return config.name();
    }

    public List<CompiledTarget> selectableTargets() {
        return targets.stream().filter(target -> target.state().isSelectable()).toList();
    }
}
