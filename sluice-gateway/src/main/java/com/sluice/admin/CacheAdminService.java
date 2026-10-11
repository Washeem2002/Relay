package com.sluice.admin;

import com.sluice.cache.CacheKeyBuilder;
import com.sluice.cache.CacheStore;
import org.springframework.stereotype.Service;

@Service
public class CacheAdminService {

    public record PurgeRequest(String key, String host, String pathPrefix) {
    }

    public record PurgeResult(String mode, long removed) {
    }

    private final CacheStore store;

    public CacheAdminService(CacheStore store) {
        this.store = store;
    }

    public PurgeResult purge(PurgeRequest request) {
        if (request.key() != null && !request.key().isBlank()) {
            return new PurgeResult("key", store.delete(request.key()) ? 1 : 0);
        }
        if (request.host() == null || request.host().isBlank() || request.pathPrefix() == null
                || !request.pathPrefix().startsWith("/")) {
            throw new IllegalArgumentException("provide either key, or host and a pathPrefix starting with /");
        }
        return new PurgeResult("prefix",
                store.deleteByPrefix(CacheKeyBuilder.prefixOf(request.host(), request.pathPrefix())));
    }
}
