package com.sluice.schema;

public record AutoRollbackConfig(int errorRatePercent, int windowSeconds, int minRequests) {

    public AutoRollbackConfig {
        if (errorRatePercent == 0) {
            errorRatePercent = 50;
        }
        if (windowSeconds == 0) {
            windowSeconds = 60;
        }
        if (minRequests == 0) {
            minRequests = 20;
        }
    }
}
