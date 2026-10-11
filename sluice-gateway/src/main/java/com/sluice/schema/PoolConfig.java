package com.sluice.schema;

import java.util.List;

public record PoolConfig(
        String name,
        LoadBalancingAlgorithm algorithm,
        String hashKey,
        List<TargetConfig> targets,
        int maxConnections,
        int maxConcurrency,
        int queueTimeoutMs,
        int connectTimeoutMs,
        String healthPath,
        int healthIntervalMs,
        int healthTimeoutMs,
        int ejectAfterFailures,
        int ejectDurationMs,
        boolean allowPrivateTargets,
        CircuitBreakerConfig circuitBreaker) {

    public PoolConfig {
        if (algorithm == null) {
            algorithm = LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN;
        }
        if (hashKey == null || hashKey.isBlank()) {
            hashKey = "client_ip";
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (maxConnections == 0) {
            maxConnections = 100;
        }
        if (maxConcurrency == 0) {
            maxConcurrency = 200;
        }
        if (queueTimeoutMs == 0) {
            queueTimeoutMs = 50;
        }
        if (connectTimeoutMs == 0) {
            connectTimeoutMs = 2_000;
        }
        if (healthIntervalMs == 0) {
            healthIntervalMs = 5_000;
        }
        if (healthTimeoutMs == 0) {
            healthTimeoutMs = 2_000;
        }
        if (ejectAfterFailures == 0) {
            ejectAfterFailures = 3;
        }
        if (ejectDurationMs == 0) {
            ejectDurationMs = 10_000;
        }
        if (circuitBreaker == null) {
            circuitBreaker = CircuitBreakerConfig.defaults();
        }
    }
}
