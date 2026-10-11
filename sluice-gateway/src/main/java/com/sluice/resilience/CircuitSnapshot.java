package com.sluice.resilience;

public record CircuitSnapshot(
        CircuitState state,
        long windowRequests,
        long windowFailures,
        int failureRatePercent,
        long openedAtEpochMillis) {
}
