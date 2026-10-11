package com.sluice.schema;

public record RateLimitPolicy(RateLimitAlgorithm algorithm, long limit, int windowSeconds, long burst) {

    public RateLimitPolicy {
        if (algorithm == null) {
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW;
        }
        if (windowSeconds == 0) {
            windowSeconds = 60;
        }
        if (burst == 0) {
            burst = limit;
        }
    }
}
