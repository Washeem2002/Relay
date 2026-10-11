package com.sluice.schema;

import java.util.List;

public record CachePolicyConfig(
        String name,
        int ttlSeconds,
        int staleWhileRevalidateSeconds,
        long maxBodyBytes,
        List<String> varyHeaders,
        boolean allowAuthorized) {

    public CachePolicyConfig {
        if (ttlSeconds == 0) {
            ttlSeconds = 60;
        }
        if (maxBodyBytes == 0) {
            maxBodyBytes = 1_048_576;
        }
        varyHeaders = varyHeaders == null ? List.of() : List.copyOf(varyHeaders);
    }
}
