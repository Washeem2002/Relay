package com.sluice.cache;

import com.sluice.schema.CachePolicyConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class CachePolicy {

    private static final Set<Integer> CACHEABLE_STATUSES = Set.of(200, 203, 204, 300, 301, 308, 404, 410);

    private final CachePolicyConfig config;

    public CachePolicy(CachePolicyConfig config) {
        this.config = config;
    }

    public CachePolicyConfig config() {
        return config;
    }

    public boolean requestEligible(String method, Function<String, String> requestHeader) {
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return false;
        }
        if (requestHeader.apply("Authorization") != null && !config.allowAuthorized()) {
            return false;
        }
        if (requestHeader.apply("Range") != null) {
            return false;
        }
        String cacheControl = lower(requestHeader.apply("Cache-Control"));
        return !(cacheControl.contains("no-cache") || cacheControl.contains("no-store"));
    }

    public Optional<Long> storableFreshMillis(int status, Map<String, List<String>> responseHeaders) {
        if (!CACHEABLE_STATUSES.contains(status)) {
            return Optional.empty();
        }
        if (responseHeaders.containsKey("Set-Cookie")) {
            return Optional.empty();
        }
        if (first(responseHeaders, "Vary").trim().equals("*")) {
            return Optional.empty();
        }
        String cacheControl = lower(first(responseHeaders, "Cache-Control"));
        if (cacheControl.contains("no-store") || cacheControl.contains("private") || cacheControl.contains("no-cache")) {
            return Optional.empty();
        }
        long ttlSeconds = config.ttlSeconds();
        Long upstreamTtl = directive(cacheControl, "s-maxage");
        if (upstreamTtl == null) {
            upstreamTtl = directive(cacheControl, "max-age");
        }
        if (upstreamTtl != null) {
            ttlSeconds = Math.min(ttlSeconds, upstreamTtl);
        }
        if (ttlSeconds <= 0) {
            return Optional.empty();
        }
        return Optional.of(ttlSeconds * 1000);
    }

    public long staleWindowMillis() {
        return config.staleWhileRevalidateSeconds() * 1000L;
    }

    public boolean bodyWithinLimit(long length) {
        return length <= config.maxBodyBytes();
    }

    private static Long directive(String cacheControl, String name) {
        for (String part : cacheControl.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                try {
                    return Long.parseLong(trimmed.substring(name.length() + 1).replace("\"", "").trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
