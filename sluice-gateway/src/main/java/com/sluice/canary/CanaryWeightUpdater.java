package com.sluice.canary;

public interface CanaryWeightUpdater {

    boolean rollbackCanary(String routeName, String reason);
}
