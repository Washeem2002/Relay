package com.sluice.support;

import com.sluice.schema.AuthConfig;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.LoadBalancingAlgorithm;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import java.util.List;
import java.util.Set;

public final class Configs {

    private Configs() {
    }

    public static PoolConfig pool(String name, String... urls) {
        List<TargetConfig> targets = new java.util.ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            targets.add(new TargetConfig("t" + (i + 1), urls[i], 1));
        }
        return new PoolConfig(name, LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, targets, 0, 0, 0, 0, null, 0,
                0, 0, 0, true, CircuitBreakerConfig.defaults());
    }

    public static PoolConfig pool(String name, LoadBalancingAlgorithm algorithm, String healthPath, int healthInterval,
                                  CircuitBreakerConfig circuit, String... urls) {
        List<TargetConfig> targets = new java.util.ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            targets.add(new TargetConfig("t" + (i + 1), urls[i], 1));
        }
        return new PoolConfig(name, algorithm, null, targets, 0, 0, 0, 0, healthPath, healthInterval, 0, 0, 0, true,
                circuit == null ? CircuitBreakerConfig.defaults() : circuit);
    }

    public static RouteConfig route(String name, String prefix, String pool) {
        return new RouteConfig(name, null, prefix, Set.of(), false, null, pool, 0, RetryConfig.defaults(),
                AuthConfig.none(), null, null, null);
    }

    public static RouteConfig route(String name, String prefix, String pool, boolean strip, int timeoutMs,
                                    RetryConfig retry, String cachePolicy, CanaryConfig canary) {
        return new RouteConfig(name, null, prefix, Set.of(), strip, null, pool, timeoutMs, retry, AuthConfig.none(),
                null, cachePolicy, canary);
    }

    public static CachePolicyConfig cachePolicy(String name, int ttl, int swr) {
        return new CachePolicyConfig(name, ttl, swr, 1_048_576, List.of(), false);
    }

    public static ConfigDocument document(List<PoolConfig> pools, List<RouteConfig> routes) {
        return new ConfigDocument(pools, routes, null, null, null, null);
    }
}
