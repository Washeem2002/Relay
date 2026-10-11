package com.sluice.canary;

public record CanaryDecision(boolean useCanary, String reason) {

    public static CanaryDecision stable(String reason) {
        return new CanaryDecision(false, reason);
    }

    public static CanaryDecision canary(String reason) {
        return new CanaryDecision(true, reason);
    }
}
