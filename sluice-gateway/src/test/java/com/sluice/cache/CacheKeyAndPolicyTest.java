package com.sluice.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.CachePolicyConfig;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CacheKeyAndPolicyTest {

    private static String key(String query, Map<String, String> headers, String... vary) {
        return CacheKeyBuilder.build("GET", "Example.com", "/a/b", query, List.of(vary),
                name -> headers.get(name.toLowerCase()));
    }

    private static CachePolicy policy(int ttl, int swr, boolean allowAuthorized) {
        return new CachePolicy(new CachePolicyConfig("p", ttl, swr, 1000, List.of(), allowAuthorized));
    }

    private static Map<String, List<String>> headers(String... pairs) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < pairs.length; i += 2) {
            map.computeIfAbsent(pairs[i], k -> new java.util.ArrayList<>()).add(pairs[i + 1]);
        }
        return map;
    }

    @Test
    void queryParameterOrderDoesNotChangeKey() {
        assertThat(key("b=2&a=1", Map.of())).isEqualTo(key("a=1&b=2", Map.of()));
        assertThat(key("a=1&a=2", Map.of())).isEqualTo(key("a=2&a=1", Map.of()));
    }

    @Test
    void differentQueriesProduceDifferentKeys() {
        assertThat(key("a=1", Map.of())).isNotEqualTo(key("a=2", Map.of()));
        assertThat(key("", Map.of())).isNotEqualTo(key("a=1", Map.of()));
    }

    @Test
    void percentEncodingIsNormalized() {
        assertThat(key("a=%2f", Map.of())).isEqualTo(key("a=%2F", Map.of()));
        assertThat(key("a=%41", Map.of())).isEqualTo(key("a=A", Map.of()));
        assertThat(key("a=%zz", Map.of())).isEqualTo(key("a=%zz", Map.of()));
        assertThat(key("a=%4", Map.of())).isEqualTo(key("a=%4", Map.of()));
    }

    @Test
    void emptyAndNullQueriesAreEquivalentAndEmptyPairsDropped() {
        assertThat(key(null, Map.of())).isEqualTo(key("", Map.of()));
        assertThat(key("a=1&&b=2", Map.of())).isEqualTo(key("a=1&b=2", Map.of()));
    }

    @Test
    void hostIsCaseInsensitiveAndMethodIsPartOfKey() {
        String get = CacheKeyBuilder.build("GET", "EXAMPLE.com", "/a", "", List.of(), n -> null);
        String lower = CacheKeyBuilder.build("GET", "example.com", "/a", "", List.of(), n -> null);
        String head = CacheKeyBuilder.build("HEAD", "example.com", "/a", "", List.of(), n -> null);
        assertThat(get).isEqualTo(lower);
        assertThat(head).isNotEqualTo(lower);
    }

    @Test
    void varyHeadersSplitKeysAndIgnoreCaseAndWhitespace() {
        String english = key("", Map.of("accept-language", "en"), "Accept-Language");
        String french = key("", Map.of("accept-language", "fr"), "Accept-Language");
        String spaced = key("", Map.of("accept-language", "  en  "), "accept-language");
        String other = key("", Map.of("accept-language", "fr", "x-other", "1"), "Accept-Language");
        assertThat(english).isNotEqualTo(french);
        assertThat(english).isEqualTo(spaced);
        assertThat(french).isEqualTo(other);
        assertThat(key("", Map.of(), "Accept-Language")).isNotEqualTo(english);
    }

    @Test
    void varyHeaderOrderDoesNotMatter() {
        Map<String, String> map = Map.of("a", "1", "b", "2");
        assertThat(key("", map, "A", "B")).isEqualTo(key("", map, "B", "A"));
    }

    @Test
    void globEscapeProtectsPatternCharacters() {
        assertThat(CacheKeyBuilder.escapeGlob("a*b?c[d]\\")).isEqualTo("a\\*b\\?c\\[d\\]\\\\");
        assertThat(CacheKeyBuilder.prefixOf("Host.COM", "/p")).isEqualTo("host.com|/p");
    }

    @Test
    void requestEligibilityRules() {
        CachePolicy policy = policy(60, 0, false);
        assertThat(policy.requestEligible("GET", name -> null)).isTrue();
        assertThat(policy.requestEligible("HEAD", name -> null)).isTrue();
        assertThat(policy.requestEligible("POST", name -> null)).isFalse();
        assertThat(policy.requestEligible("GET", name -> name.equals("Authorization") ? "Bearer x" : null)).isFalse();
        assertThat(policy.requestEligible("GET", name -> name.equals("Cache-Control") ? "no-cache" : null)).isFalse();
        assertThat(policy.requestEligible("GET", name -> name.equals("Range") ? "bytes=0-1" : null)).isFalse();
        assertThat(policy(60, 0, true).requestEligible("GET", name -> name.equals("Authorization") ? "x" : null))
                .isTrue();
    }

    @Test
    void responseStorabilityRules() {
        CachePolicy policy = policy(60, 5, false);
        assertThat(policy.storableFreshMillis(200, headers())).contains(60_000L);
        assertThat(policy.storableFreshMillis(500, headers())).isEmpty();
        assertThat(policy.storableFreshMillis(206, headers())).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "no-store"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "private, max-age=10"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "no-cache"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Set-Cookie", "a=b"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Vary", "*"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "public, max-age=10"))).contains(10_000L);
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "max-age=600"))).contains(60_000L);
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "max-age=0"))).isEmpty();
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "max-age=5, s-maxage=20")))
                .contains(20_000L);
        assertThat(policy.storableFreshMillis(200, headers("Cache-Control", "max-age=abc"))).contains(60_000L);
        assertThat(policy.staleWindowMillis()).isEqualTo(5_000);
        assertThat(policy.bodyWithinLimit(1000)).isTrue();
        assertThat(policy.bodyWithinLimit(1001)).isFalse();
        assertThat(policy.config().name()).isEqualTo("p");
    }

    @Test
    void entrySerializationRoundTrips() {
        Map<String, List<String>> headers = headers("Content-Type", "text/plain", "X-Multi", "a", "X-Multi", "b");
        CacheEntry entry = new CacheEntry(200, headers, "hello".getBytes(), 10, 20, 30);
        CacheEntry copy = CacheEntry.deserialize(entry.serialize());
        assertThat(copy.status()).isEqualTo(200);
        assertThat(copy.body()).isEqualTo("hello".getBytes());
        assertThat(copy.headers().get("x-multi")).containsExactly("a", "b");
        assertThat(copy.storedAtMillis()).isEqualTo(10);
        assertThat(copy.isFresh(19)).isTrue();
        assertThat(copy.isFresh(20)).isFalse();
        assertThat(copy.isServableStale(20)).isTrue();
        assertThat(copy.isServableStale(30)).isFalse();
    }
}
