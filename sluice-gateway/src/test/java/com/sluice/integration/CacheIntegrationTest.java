package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.cache.CacheKeyBuilder;
import com.sluice.mock.MockUpstream;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class CacheIntegrationTest extends GatewayIntegrationTest {

    private static final MockUpstream A = TestEnvironment.UPSTREAM_A;

    private void configure(CachePolicyConfig policy) {
        RouteConfig route = Configs.route("cache", "/cache", "cache-pool", true, 0, RetryConfig.defaults(),
                policy.name(), null);
        applyConfig(new ConfigDocument(List.of(Configs.pool("cache-pool", A.baseUrl())), List.of(route), List.of(),
                List.of(), List.of(policy), null));
    }

    private static CachePolicyConfig policy(int ttl, int swr) {
        return new CachePolicyConfig("policy", ttl, swr, 1_048_576, List.of(), false);
    }

    private int version(Http.Response response) {
        return json(response).path("version").asInt();
    }

    @Test
    void firstRequestMissesThenServesFromCacheWithoutTouchingTheUpstream() {
        configure(policy(60, 0));
        Http.Response miss = gateway("/cache/cacheable");
        assertThat(miss.status()).isEqualTo(200);
        assertThat(miss.header("X-Cache")).isEqualTo("MISS");
        assertThat(version(miss)).isEqualTo(1);
        Http.Response hit = gateway("/cache/cacheable");
        assertThat(hit.header("X-Cache")).isEqualTo("HIT");
        assertThat(version(hit)).isEqualTo(1);
        assertThat(hit.header("Age")).isNotNull();
        assertThat(hit.header("Via")).contains("1.1 sluice");
        assertThat(hit.header("Content-Type")).contains("application/json");
        assertThat(A.hits("/cacheable")).isEqualTo(1);
    }

    @Test
    void servesStaleContentWhileRevalidatingInTheBackground() throws InterruptedException {
        configure(policy(1, 10));
        assertThat(gateway("/cache/cacheable").header("X-Cache")).isEqualTo("MISS");
        assertThat(gateway("/cache/cacheable").header("X-Cache")).isEqualTo("HIT");
        Thread.sleep(1_300);
        Http.Response stale = gateway("/cache/cacheable");
        assertThat(stale.header("X-Cache")).isEqualTo("STALE");
        assertThat(version(stale)).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> A.hits("/cacheable") == 2);
        Http.Response refreshed = gateway("/cache/cacheable");
        assertThat(refreshed.header("X-Cache")).isEqualTo("HIT");
        assertThat(version(refreshed)).isEqualTo(2);
        assertThat(A.hits("/cacheable")).isEqualTo(2);
    }

    @Test
    void expiredEntriesBeyondTheStaleWindowAreMissesAgain() throws InterruptedException {
        configure(policy(1, 0));
        gateway("/cache/cacheable");
        Thread.sleep(1_300);
        Http.Response response = gateway("/cache/cacheable");
        assertThat(response.header("X-Cache")).isEqualTo("MISS");
        assertThat(version(response)).isEqualTo(2);
    }

    @Test
    void concurrentMissesAreCoalescedIntoOneUpstreamRequest() throws Exception {
        configure(policy(60, 0));
        int callers = 30;
        List<Future<Http.Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> gateway("/cache/cacheable?delayMs=700")));
            }
            Set<Integer> versions = new HashSet<>();
            int misses = 0;
            int hits = 0;
            for (Future<Http.Response> future : futures) {
                Http.Response response = future.get();
                assertThat(response.status()).isEqualTo(200);
                versions.add(version(response));
                if ("MISS".equals(response.header("X-Cache"))) {
                    misses++;
                } else if ("HIT".equals(response.header("X-Cache"))) {
                    hits++;
                }
            }
            assertThat(versions).containsExactly(1);
            assertThat(misses).isEqualTo(1);
            assertThat(hits).isEqualTo(callers - 1);
        }
        assertThat(A.hits("/cacheable")).isEqualTo(1);
    }

    @Test
    void varyHeadersSplitCacheEntries() {
        configure(new CachePolicyConfig("policy", 60, 0, 1_048_576, List.of("Accept-Language"), false));
        assertThat(gateway("/cache/cacheable", "Accept-Language", "en").header("X-Cache")).isEqualTo("MISS");
        assertThat(gateway("/cache/cacheable", "Accept-Language", "fr").header("X-Cache")).isEqualTo("MISS");
        assertThat(gateway("/cache/cacheable", "Accept-Language", "en").header("X-Cache")).isEqualTo("HIT");
        assertThat(gateway("/cache/cacheable", "Accept-Language", "fr").header("X-Cache")).isEqualTo("HIT");
        assertThat(A.hits("/cacheable")).isEqualTo(2);
    }

    @Test
    void queryParameterOrderDoesNotSplitTheCache() {
        configure(policy(60, 0));
        assertThat(gateway("/cache/cacheable?a=1&b=2").header("X-Cache")).isEqualTo("MISS");
        assertThat(gateway("/cache/cacheable?b=2&a=1").header("X-Cache")).isEqualTo("HIT");
        assertThat(gateway("/cache/cacheable?a=1&b=3").header("X-Cache")).isEqualTo("MISS");
    }

    @Test
    void authorizedPostAndNoCacheRequestsBypassTheCache() {
        configure(policy(60, 0));
        gateway("/cache/cacheable");
        Http.Response authorized = gateway("/cache/cacheable", "Authorization", "Bearer token");
        assertThat(authorized.header("X-Cache")).isEqualTo("BYPASS");
        assertThat(version(authorized)).isEqualTo(2);
        Http.Response noCache = gateway("/cache/cacheable", "Cache-Control", "no-cache");
        assertThat(noCache.header("X-Cache")).isEqualTo("BYPASS");
        Http.Response post = Http.send("POST", TestEnvironment.gatewayUrl("/cache/cacheable"), "x");
        assertThat(post.header("X-Cache")).isEqualTo("BYPASS");
        assertThat(gateway("/cache/cacheable").header("X-Cache")).isEqualTo("HIT");
    }

    @Test
    void authorizedRequestsAreCachedOnlyWhenThePolicyAllowsIt() {
        configure(new CachePolicyConfig("policy", 60, 0, 1_048_576, List.of("Authorization"), true));
        assertThat(gateway("/cache/cacheable", "Authorization", "Bearer one").header("X-Cache")).isEqualTo("MISS");
        assertThat(gateway("/cache/cacheable", "Authorization", "Bearer one").header("X-Cache")).isEqualTo("HIT");
        assertThat(gateway("/cache/cacheable", "Authorization", "Bearer two").header("X-Cache")).isEqualTo("MISS");
    }

    @Test
    void responsesMarkedNoStoreOrPrivateAreNeverCached() {
        configure(policy(60, 0));
        for (String directive : new String[] {"no-store", "private"}) {
            A.resetCounters();
            assertThat(gateway("/cache/cacheable?cc=" + directive).header("X-Cache")).isEqualTo("MISS");
            assertThat(gateway("/cache/cacheable?cc=" + directive).header("X-Cache")).isEqualTo("MISS");
            assertThat(A.hits("/cacheable")).isEqualTo(2);
        }
    }

    @Test
    void errorsAndOversizedBodiesAreNotCached() {
        configure(new CachePolicyConfig("policy", 60, 0, 100, List.of(), false));
        gateway("/cache/error?status=500");
        gateway("/cache/error?status=500");
        assertThat(A.hits("/error")).isEqualTo(2);
        Http.Response first = gateway("/cache/big?bytes=5000");
        Http.Response second = gateway("/cache/big?bytes=5000");
        assertThat(first.body()).hasSize(5000);
        assertThat(second.header("X-Cache")).isEqualTo("MISS");
        assertThat(A.hits("/big")).isEqualTo(2);
    }

    @Test
    void headRequestsAreCachedWithoutABody() {
        configure(policy(60, 0));
        Http.Response miss = Http.send("HEAD", TestEnvironment.gatewayUrl("/cache/cacheable"), null);
        assertThat(miss.status()).isEqualTo(200);
        assertThat(miss.header("X-Cache")).isEqualTo("MISS");
        Http.Response hit = Http.send("HEAD", TestEnvironment.gatewayUrl("/cache/cacheable"), null);
        assertThat(hit.header("X-Cache")).isEqualTo("HIT");
        assertThat(hit.body()).isEmpty();
        assertThat(A.hits("/cacheable")).isEqualTo(1);
    }

    @Test
    void purgeByPrefixAndByKeyEvictsEntries() {
        configure(policy(60, 0));
        gateway("/cache/cacheable?x=1");
        gateway("/cache/cacheable?x=2");
        assertThat(gateway("/cache/cacheable?x=1").header("X-Cache")).isEqualTo("HIT");

        Http.Response byPrefix = admin("POST", "/admin/cache/purge",
                "{\"host\":\"127.0.0.1\",\"pathPrefix\":\"/cache/cacheable\"}");
        assertThat(byPrefix.status()).isEqualTo(200);
        assertThat(json(byPrefix).path("removed").asInt()).isEqualTo(2);
        assertThat(gateway("/cache/cacheable?x=1").header("X-Cache")).isEqualTo("MISS");

        String key = CacheKeyBuilder.build("GET", "127.0.0.1", "/cache/cacheable", "x=1", List.of(), name -> null);
        Http.Response byKey = admin("POST", "/admin/cache/purge", "{\"key\":\"" + key + "\"}");
        assertThat(json(byKey).path("removed").asInt()).isEqualTo(1);
        assertThat(gateway("/cache/cacheable?x=1").header("X-Cache")).isEqualTo("MISS");
        assertThat(admin("POST", "/admin/cache/purge", "{}").status()).isEqualTo(400);
    }
}
