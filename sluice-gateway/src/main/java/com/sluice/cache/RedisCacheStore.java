package com.sluice.cache;

import com.sluice.config.SluiceProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

@Component
public class RedisCacheStore implements CacheStore {

    private static final int DELETE_BATCH = 500;

    private final RedisTemplate<String, byte[]> redis;
    private final String prefix;

    public RedisCacheStore(RedisTemplate<String, byte[]> cacheRedisTemplate, SluiceProperties properties) {
        this.redis = cacheRedisTemplate;
        this.prefix = properties.cache().keyPrefix();
    }

    @Override
    public Optional<CacheEntry> get(String key) {
        byte[] raw = redis.opsForValue().get(prefix + key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CacheEntry.deserialize(raw));
        } catch (RuntimeException e) {
            redis.delete(prefix + key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, CacheEntry entry, long retainMillis) {
        redis.opsForValue().set(prefix + key, entry.serialize(), Duration.ofMillis(Math.max(1, retainMillis)));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(prefix + key));
    }

    @Override
    public long deleteByPrefix(String hostAndPathPrefix) {
        String pattern = prefix + CacheKeyBuilder.escapeGlob(hostAndPathPrefix) + "*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(DELETE_BATCH).build();
        long deleted = 0;
        List<String> batch = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= DELETE_BATCH) {
                    deleted += deleteBatch(batch);
                }
            }
        }
        return deleted + deleteBatch(batch);
    }

    private long deleteBatch(List<String> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(new ArrayList<>(batch));
        batch.clear();
        return removed == null ? 0 : removed;
    }
}
