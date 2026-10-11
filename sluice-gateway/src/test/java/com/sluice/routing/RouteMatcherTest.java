package com.sluice.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.RouteConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {

    private static RouteConfig route(String name, String host, String prefix, String... methods) {
        return new RouteConfig(name, host, prefix, Set.of(methods), false, null, "pool", 0, null, null, null, null,
                null);
    }

    private static String match(RouteMatcher matcher, String host, String path, String method) {
        return matcher.match(host, path, method).map(RouteConfig::name).orElse("none");
    }

    @Test
    void longestPrefixWins() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("root", null, "/"),
                route("api", null, "/api"),
                route("users", null, "/api/users")));
        assertThat(match(matcher, "h", "/api/users/42", "GET")).isEqualTo("users");
        assertThat(match(matcher, "h", "/api/orders", "GET")).isEqualTo("api");
        assertThat(match(matcher, "h", "/other", "GET")).isEqualTo("root");
    }

    @Test
    void prefixMatchesOnSegmentBoundaryOnly() {
        RouteMatcher matcher = RouteMatcher.build(List.of(route("api", null, "/api")));
        assertThat(match(matcher, "h", "/api", "GET")).isEqualTo("api");
        assertThat(match(matcher, "h", "/api/", "GET")).isEqualTo("api");
        assertThat(match(matcher, "h", "/apix", "GET")).isEqualTo("none");
    }

    @Test
    void noMatchWhenNothingApplies() {
        RouteMatcher matcher = RouteMatcher.build(List.of(route("api", null, "/api")));
        assertThat(match(matcher, "h", "/zzz", "GET")).isEqualTo("none");
        assertThat(RouteMatcher.build(List.of()).match("h", "/", "GET")).isEmpty();
    }

    @Test
    void methodRestrictionIsHonouredAndFallsBackToShorterPrefix() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("api", null, "/api"),
                route("write", null, "/api/items", "POST", "PUT")));
        assertThat(match(matcher, "h", "/api/items", "POST")).isEqualTo("write");
        assertThat(match(matcher, "h", "/api/items", "GET")).isEqualTo("api");
    }

    @Test
    void methodSpecificBeatsAnyMethodOnSamePrefix() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("any", null, "/x"),
                route("get-only", null, "/x", "GET")));
        assertThat(match(matcher, "h", "/x", "GET")).isEqualTo("get-only");
        assertThat(match(matcher, "h", "/x", "DELETE")).isEqualTo("any");
    }

    @Test
    void headMatchesGetRoutes() {
        RouteMatcher matcher = RouteMatcher.build(List.of(route("get-only", null, "/x", "GET")));
        assertThat(match(matcher, "h", "/x", "HEAD")).isEqualTo("get-only");
        assertThat(match(matcher, "h", "/x", "POST")).isEqualTo("none");
    }

    @Test
    void exactHostBeatsWildcardBeatsAnyOnSamePrefix() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("any", null, "/a"),
                route("wild", "*.example.com", "/a"),
                route("exact", "api.example.com", "/a")));
        assertThat(match(matcher, "api.example.com", "/a", "GET")).isEqualTo("exact");
        assertThat(match(matcher, "other.example.com", "/a", "GET")).isEqualTo("wild");
        assertThat(match(matcher, "example.org", "/a", "GET")).isEqualTo("any");
        assertThat(match(matcher, "example.com", "/a", "GET")).isEqualTo("any");
    }

    @Test
    void longerPrefixBeatsMoreSpecificHost() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("exact-short", "api.example.com", "/a"),
                route("any-long", null, "/a/b")));
        assertThat(match(matcher, "api.example.com", "/a/b/c", "GET")).isEqualTo("any-long");
    }

    @Test
    void longerWildcardSuffixWins() {
        RouteMatcher matcher = RouteMatcher.build(List.of(
                route("outer", "*.example.com", "/a"),
                route("inner", "*.eu.example.com", "/a")));
        assertThat(match(matcher, "x.eu.example.com", "/a", "GET")).isEqualTo("inner");
        assertThat(match(matcher, "x.us.example.com", "/a", "GET")).isEqualTo("outer");
    }

    @Test
    void ties_breakDeterministicallyByName() {
        List<RouteConfig> routes = List.of(route("zeta", null, "/t"), route("alpha", null, "/t"), route("mid", null, "/t"));
        assertThat(match(RouteMatcher.build(routes), "h", "/t", "GET")).isEqualTo("alpha");
        assertThat(match(RouteMatcher.build(List.of(routes.get(2), routes.get(0), routes.get(1))), "h", "/t", "GET"))
                .isEqualTo("alpha");
    }

    @Test
    void starHostIsTreatedAsAnyHost() {
        RouteMatcher matcher = RouteMatcher.build(List.of(route("star", "*", "/s")));
        assertThat(match(matcher, "whatever", "/s", "GET")).isEqualTo("star");
    }

    @Test
    void pathNormalizerDetectsTraversalAndStripsPorts() {
        assertThat(PathNormalizer.containsTraversal("/a/../b")).isTrue();
        assertThat(PathNormalizer.containsTraversal("/a/%2e%2e/b")).isTrue();
        assertThat(PathNormalizer.containsTraversal("/a/%2E%2e/b")).isTrue();
        assertThat(PathNormalizer.containsTraversal("/a/b%2fc")).isTrue();
        assertThat(PathNormalizer.containsTraversal("/a/b.c/d")).isFalse();
        assertThat(PathNormalizer.containsTraversal(null)).isFalse();
        assertThat(PathNormalizer.hostWithoutPort("Example.com:8080")).isEqualTo("example.com");
        assertThat(PathNormalizer.hostWithoutPort("[::1]:8080")).isEqualTo("[::1]");
        assertThat(PathNormalizer.hostWithoutPort(null)).isEmpty();
        assertThat(PathNormalizer.segments(null)).isEmpty();
        assertThat(PathNormalizer.segments("//a//b/")).containsExactly("a", "b");
    }
}
