package com.sluice.schema;

public record CanaryConfig(String pool, int weight, String stickyKey, AutoRollbackConfig autoRollback) {

    public CanaryConfig {
        if (stickyKey == null || stickyKey.isBlank()) {
            stickyKey = "api_key";
        }
    }

    public CanaryConfig withWeight(int newWeight) {
        return new CanaryConfig(pool, newWeight, stickyKey, autoRollback);
    }
}
