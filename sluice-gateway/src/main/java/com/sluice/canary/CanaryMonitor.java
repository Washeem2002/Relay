package com.sluice.canary;

import com.sluice.resilience.RollingCounter;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class CanaryMonitor {

    private static final int BUCKETS = 12;

    private record Window(int windowSeconds, RollingCounter counter) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public CanaryMonitor(Clock clock) {
        this.clock = clock;
    }

    public void record(String routeName, int windowSeconds, boolean error) {
        windowFor(routeName, windowSeconds).counter().record(error);
    }

    public RollingCounter.Counts counts(String routeName, int windowSeconds) {
        return windowFor(routeName, windowSeconds).counter().counts();
    }

    public void reset(String routeName) {
        windows.remove(routeName);
    }

    private Window windowFor(String routeName, int windowSeconds) {
        return windows.compute(routeName, (key, existing) -> {
            if (existing != null && existing.windowSeconds() == windowSeconds) {
                return existing;
            }
            return new Window(windowSeconds, new RollingCounter(clock, windowSeconds * 1000L, BUCKETS));
        });
    }
}
