package com.sluice.cache;

import java.util.Optional;

public interface CacheStore {

    Optional<CacheEntry> get(String key);

    void put(String key, CacheEntry entry, long retainMillis);

    boolean delete(String key);

    long deleteByPrefix(String hostAndPathPrefix);
}
