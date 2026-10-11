package com.sluice.balancing;

import com.sluice.config.SluiceProperties;
import com.sluice.schema.PoolConfig;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class PoolRuntimeRegistry {

    private final Map<String, PoolRuntime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retirement = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pool-retirement");
        thread.setDaemon(true);
        return thread;
    });
    private final long graceMillis;

    public PoolRuntimeRegistry(SluiceProperties properties) {
        this.graceMillis = properties.gateway().retiredPoolGraceMs();
    }

    public synchronized PoolRuntime obtain(PoolConfig config) {
        PoolRuntime existing = runtimes.get(config.name());
        if (existing != null && existing.fingerprint().equals(PoolRuntime.fingerprint(config))) {
            return existing;
        }
        PoolRuntime created = new PoolRuntime(config);
        runtimes.put(config.name(), created);
        if (existing != null) {
            retire(existing);
        }
        return created;
    }

    public synchronized void retainOnly(Set<String> poolNames) {
        runtimes.entrySet().removeIf(entry -> {
            if (poolNames.contains(entry.getKey())) {
                return false;
            }
            retire(entry.getValue());
            return true;
        });
    }

    @PreDestroy
    public synchronized void shutdown() {
        runtimes.values().forEach(PoolRuntime::close);
        runtimes.clear();
        retirement.shutdownNow();
    }

    private void retire(PoolRuntime runtime) {
        retirement.schedule(runtime::close, graceMillis, TimeUnit.MILLISECONDS);
    }
}
