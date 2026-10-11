package com.sluice.balancing;

import com.sluice.observability.SluiceMetrics;
import com.sluice.schema.CircuitBreakerConfig;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TargetStateRegistry {

    private final Map<String, TargetState> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SluiceMetrics metrics;

    public TargetStateRegistry(Clock clock, SluiceMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
    }

    public TargetState obtain(String pool, String targetId, String url, CircuitBreakerConfig circuitConfig) {
        TargetState state = states.computeIfAbsent(keyOf(pool, targetId, url), key -> {
            TargetState created = new TargetState(pool, targetId, circuitConfig, clock);
            metrics.registerTarget(created);
            return created;
        });
        state.configureCircuit(circuitConfig);
        return state;
    }

    public void retainOnly(Set<String> keys) {
        states.keySet().retainAll(keys);
    }

    public static String keyOf(String pool, String targetId, String url) {
        return pool + "|" + targetId + "|" + url;
    }

    public java.util.Collection<TargetState> all() {
        return states.values();
    }
}
