package com.sluice.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.config.SluiceProperties;
import com.sluice.infra.RedisConfig;
import com.sluice.support.TestRedis;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

class RedisCacheStoreTest {

    private static RedisCacheStore store;
    private static RedisTemplate<String, byte[]> template;
    private static String namespace;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", TestRedis.start());
        factory.afterPropertiesSet();
        template = new RedisConfig().cacheRedisTemplate(factory);
        template.afterPropertiesSet();
        namespace = "t" + UUID.randomUUID().toString().substring(0, 8);
        SluiceProperties properties = new SluiceProperties(null, null, null, null, null,
                new SluiceProperties.Cache("test:cache:", 1000));
        store = new RedisCacheStore(template, properties);
    }

    private static CacheEntry entry(String body) {
        return new CacheEntry(200, Map.of("Content-Type", List.of("text/plain")),
                body.getBytes(StandardCharsets.UTF_8), 1, 2, 3);
    }

    @Test
    void putThenGetReturnsEntry() {
        store.put(namespace + "|/a||GET|", entry("hello"), 5_000);
        CacheEntry loaded = store.get(namespace + "|/a||GET|").orElseThrow();
        assertThat(new String(loaded.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(loaded.headers().get("Content-Type")).containsExactly("text/plain");
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(store.get(namespace + "-missing")).isEmpty();
    }

    @Test
    void entriesExpireAfterRetention() throws InterruptedException {
        store.put(namespace + "-ttl", entry("x"), 150);
        assertThat(store.get(namespace + "-ttl")).isPresent();
        Thread.sleep(400);
        assertThat(store.get(namespace + "-ttl")).isEmpty();
    }

    @Test
    void deleteRemovesSingleKey() {
        store.put(namespace + "-del", entry("x"), 5_000);
        assertThat(store.delete(namespace + "-del")).isTrue();
        assertThat(store.delete(namespace + "-del")).isFalse();
        assertThat(store.get(namespace + "-del")).isEmpty();
    }

    @Test
    void deleteByPrefixRemovesOnlyMatchingEntries() {
        String host = namespace + "host";
        store.put(host + "|/products/1||GET|", entry("1"), 5_000);
        store.put(host + "|/products/2|a=1|GET|", entry("2"), 5_000);
        store.put(host + "|/orders/1||GET|", entry("3"), 5_000);
        long removed = store.deleteByPrefix(host + "|/products");
        assertThat(removed).isEqualTo(2);
        assertThat(store.get(host + "|/products/1||GET|")).isEmpty();
        assertThat(store.get(host + "|/orders/1||GET|")).isPresent();
    }

    @Test
    void deleteByPrefixWithWildcardCharactersIsLiteral() {
        String host = namespace + "glob";
        store.put(host + "|/a*b||GET|", entry("1"), 5_000);
        store.put(host + "|/axxb||GET|", entry("2"), 5_000);
        assertThat(store.deleteByPrefix(host + "|/a*")).isEqualTo(1);
        assertThat(store.get(host + "|/axxb||GET|")).isPresent();
    }

    @Test
    void corruptedEntryIsTreatedAsMissAndRemoved() {
        template.opsForValue().set("test:cache:" + namespace + "-bad", new byte[] {1, 2, 3});
        assertThat(store.get(namespace + "-bad")).isEmpty();
        assertThat(template.hasKey("test:cache:" + namespace + "-bad")).isFalse();
    }
}
