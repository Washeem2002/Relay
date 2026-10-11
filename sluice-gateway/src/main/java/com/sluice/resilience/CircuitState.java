package com.sluice.resilience;

public enum CircuitState {
    CLOSED(0),
    HALF_OPEN(1),
    OPEN(2);

    private final int metricValue;

    CircuitState(int metricValue) {
        this.metricValue = metricValue;
    }

    public int metricValue() {
        return metricValue;
    }
}
