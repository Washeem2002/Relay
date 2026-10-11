package com.sluice.routing;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class SnapshotHolder {

    private final AtomicReference<RoutingSnapshot> current = new AtomicReference<>(RoutingSnapshot.empty());

    public RoutingSnapshot current() {
        return current.get();
    }

    public RoutingSnapshot install(RoutingSnapshot snapshot) {
        return current.getAndSet(snapshot);
    }

    public long version() {
        return current.get().version();
    }
}
