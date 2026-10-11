package com.sluice.schema;

public record TargetConfig(String id, String url, int weight) {

    public TargetConfig {
        if (weight == 0) {
            weight = 1;
        }
    }
}
