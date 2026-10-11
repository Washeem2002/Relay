package com.sluice.support;

import com.sluice.balancing.CompiledTarget;
import com.sluice.balancing.TargetState;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.TargetConfig;
import java.net.URI;
import java.time.Clock;

public final class Targets {

    private Targets() {
    }

    public static CompiledTarget target(String id, int weight, Clock clock) {
        TargetConfig config = new TargetConfig(id, "http://" + id + ":8080", weight);
        return new CompiledTarget(config, URI.create(config.url()),
                new TargetState("pool", id, CircuitBreakerConfig.defaults(), clock));
    }

    public static CompiledTarget target(String id, int weight) {
        return target(id, weight, new MutableClock());
    }
}
