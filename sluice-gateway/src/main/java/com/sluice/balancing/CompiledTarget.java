package com.sluice.balancing;

import com.sluice.schema.TargetConfig;
import java.net.URI;

public record CompiledTarget(TargetConfig config, URI uri, TargetState state) {

    public String id() {
        return config.id();
    }

    public int weight() {
        return config.weight();
    }
}
