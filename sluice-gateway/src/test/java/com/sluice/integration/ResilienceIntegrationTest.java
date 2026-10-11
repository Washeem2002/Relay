package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sluice.mock.MockUpstream;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.LoadBalancingAlgorithm;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class ResilienceIntegrationTest extends GatewayIntegrationTest {

    private static final MockUpstream A = TestEnvironment.UPSTREAM_A;
    private static final MockUpstream B = TestEnvironment.UPSTREAM_B;

    private static final CircuitBreakerConfig NEVER_TRIPS = new CircuitBreakerConfig(100, 30, 100_000, 10_000, 1);

    private static PoolConfig pool(String name, LoadBalancingAlgorithm algorithm, String hashKey, String healthPath,
                                   int healthIntervalMs, int ejectAfter, int ejectMs, int maxConcurrency,
                                   CircuitBreakerConfig circuit, List<TargetConfig> targets) {
        return new PoolConfig(name, algorithm, hashKey, targets, 0, maxConcurrency, 0, 0, healthPath, healthIntervalMs,
                0, ejectAfter, ejectMs, true, circuit);
    }

    private static TargetConfig target(String id, MockUpstream upstream, int weight) {
        return new TargetConfig(id, upstream.baseUrl(), weight);
    }

    private RouteConfig route(String name, String pool, RetryConfig retry) {
        return Configs.route(name, "/" + name, pool, false, 0, retry, null, null);
    }

    private RouteConfig stripRoute(String name, String pool, RetryConfig retry) {
        return Configs.route(name, "/" + name, pool, true, 0, retry, null, null);
    }

    private JsonNode circuit(String pool, String target) {
        return json(admin("GET", "/admin/circuits/" + pool + "/" + target, null));
    }

    @Test
    void circuitOpensAfterFailuresAndRecoversThroughHalfOpen() throws Exception {
        PoolConfig pool = pool("cb", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 0,
                new CircuitBreakerConfig(50, 30, 4, 800, 1), List.of(target("t1", A, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(route("cb", "cb", new RetryConfig(1, false, 0, 0, 0)))));
        A.setFailing(true);
        for (int i = 0; i < 4; i++) {
            assertThat(gateway("/cb/x").status()).isEqualTo(503);
        }
        assertThat(A.totalHits()).isEqualTo(4);
        assertThat(circuit("cb", "t1").path("circuit").path("state").asText()).isEqualTo("OPEN");

        Http.Response failFast = gateway("/cb/x");
        assertThat(failFast.status()).isEqualTo(503);
        assertThat(json(failFast).path("error").path("code").asText()).isEqualTo("circuit_open");
        assertThat(failFast.header("Retry-After")).isNotNull();
        assertThat(A.totalHits()).isEqualTo(4);

        Thread.sleep(900);
        assertThat(gateway("/cb/x").status()).isEqualTo(503);
        assertThat(A.totalHits()).isEqualTo(5);
        assertThat(circuit("cb", "t1").path("circuit").path("state").asText()).isEqualTo("OPEN");
        assertThat(json(gateway("/cb/x")).path("error").path("code").asText()).isEqualTo("circuit_open");

        A.setFailing(false);
        Thread.sleep(900);
        assertThat(gateway("/cb/x").status()).isEqualTo(200);
        assertThat(circuit("cb", "t1").path("circuit").path("state").asText()).isEqualTo("CLOSED");
        assertThat(gateway("/cb/x").status()).isEqualTo(200);
        assertThat(Http.get(TestEnvironment.adminUrl("/metrics")).body()).contains("sluice_circuit_state{pool=\"cb\"");
    }

    @Test
    void circuitCanBeResetManuallyThroughTheAdminApi() {
        PoolConfig pool = pool("reset", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 0,
                new CircuitBreakerConfig(50, 30, 2, 60_000, 1), List.of(target("t1", A, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(route("reset", "reset", new RetryConfig(1, false, 0, 0, 0)))));
        A.setFailing(true);
        gateway("/reset/x");
        gateway("/reset/x");
        assertThat(circuit("reset", "t1").path("circuit").path("state").asText()).isEqualTo("OPEN");
        A.setFailing(false);
        assertThat(gateway("/reset/x").status()).isEqualTo(503);
        Http.Response reset = admin("POST", "/admin/circuits/reset/t1/reset", null);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(json(reset).path("circuit").path("state").asText()).isEqualTo("CLOSED");
        assertThat(gateway("/reset/x").status()).isEqualTo(200);
        assertThat(admin("GET", "/admin/audit?action=CIRCUIT_RESET", null).body()).contains("CIRCUIT_RESET");
        assertThat(admin("POST", "/admin/circuits/reset/missing/reset", null).status()).isEqualTo(404);
    }

    @Test
    void failingTargetIsEjectedPassivelyAndReturnsAfterActiveProbeSucceeds() {
        PoolConfig pool = pool("eject", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, "/health", 200, 2, 600_000, 0,
                NEVER_TRIPS, List.of(target("a", A, 1), target("b", B, 1)));
        applyConfig(Configs.document(List.of(pool),
                List.of(route("eject", "eject", new RetryConfig(2, false, 50, 100, 10)))));
        A.setFailing(true);
        A.setHealthy(false);
        for (int i = 0; i < 6; i++) {
            Http.Response response = gateway("/eject/x");
            assertThat(response.status()).as("retry should reach a healthy target").isEqualTo(200);
            assertThat(upstreamOf(response)).isEqualTo("upstream-b");
        }
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(circuit("eject", "a").path("health").asText()).isEqualTo("EJECTED"));
        long aHitsWhileEjected = A.hits("/eject/x");
        for (int i = 0; i < 10; i++) {
            assertThat(upstreamOf(gateway("/eject/x"))).isEqualTo("upstream-b");
        }
        assertThat(A.hits("/eject/x")).isEqualTo(aHitsWhileEjected);

        A.setFailing(false);
        A.setHealthy(true);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(circuit("eject", "a").path("health").asText()).isEqualTo("HEALTHY"));
        Map<String, Integer> served = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            served.merge(upstreamOf(gateway("/eject/x")), 1, Integer::sum);
        }
        assertThat(served).containsKeys("upstream-a", "upstream-b");
    }

    @Test
    void poolWithNoHealthyTargetReturns503UntilItRecovers() {
        PoolConfig pool = pool("nohealthy", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, "/health", 150, 2, 600_000,
                0, NEVER_TRIPS, List.of(target("a", A, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(route("nohealthy", "nohealthy", RetryConfig.defaults()))));
        assertThat(gateway("/nohealthy/x").status()).isEqualTo(200);
        A.setHealthy(false);
        Awaitility.await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            Http.Response response = gateway("/nohealthy/x");
            assertThat(response.status()).isEqualTo(503);
            assertThat(json(response).path("error").path("code").asText()).isEqualTo("no_healthy_target");
        });
        A.setHealthy(true);
        Awaitility.await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(gateway("/nohealthy/x").status()).isEqualTo(200));
    }

    @Test
    void retriesOnlyIdempotentMethodsAndNeverAfterTheBodyWasSent() {
        PoolConfig pool = pool("retry", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 0,
                NEVER_TRIPS, List.of(target("a", A, 1)));
        applyConfig(Configs.document(List.of(pool),
                List.of(route("retry", "retry", new RetryConfig(3, false, 100, 100, 10)))));
        A.setFailing(true);

        assertThat(gateway("/retry/get").status()).isEqualTo(503);
        assertThat(A.hits("/retry/get")).isEqualTo(3);

        assertThat(Http.send("POST", TestEnvironment.gatewayUrl("/retry/post"), "payload").status()).isEqualTo(503);
        assertThat(A.hits("/retry/post")).isEqualTo(1);

        assertThat(Http.send("DELETE", TestEnvironment.gatewayUrl("/retry/delete"), null).status()).isEqualTo(503);
        assertThat(A.hits("/retry/delete")).isEqualTo(3);

        assertThat(Http.send("PUT", TestEnvironment.gatewayUrl("/retry/put"), "streamed body").status()).isEqualTo(503);
        assertThat(A.hits("/retry/put")).isEqualTo(1);
    }

    @Test
    void retryBudgetStopsRetriesFromAmplifyingAnOutage() {
        PoolConfig pool = pool("budget", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 0,
                NEVER_TRIPS, List.of(target("a", A, 1)));
        applyConfig(Configs.document(List.of(pool),
                List.of(route("budget", "budget", new RetryConfig(2, false, 10, 1, 60)))));
        A.setFailing(true);
        int requests = 40;
        for (int i = 0; i < requests; i++) {
            assertThat(gateway("/budget/x").status()).isEqualTo(503);
        }
        long upstreamCalls = A.hits("/budget/x");
        assertThat(upstreamCalls).isGreaterThan(requests);
        assertThat(upstreamCalls).isLessThanOrEqualTo(requests + 8);
    }

    @Test
    void bulkheadRejectsOverflowInsteadOfExhaustingTheGateway() throws Exception {
        PoolConfig pool = pool("bulk", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 2,
                NEVER_TRIPS, List.of(target("a", A, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(stripRoute("bulk", "bulk", new RetryConfig(1, false, 0, 0, 0)))));
        List<Future<Http.Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> gateway("/bulk/slow?ms=800")));
            }
            int ok = 0;
            int rejected = 0;
            for (Future<Http.Response> future : futures) {
                Http.Response response = future.get();
                if (response.status() == 200) {
                    ok++;
                } else if (response.status() == 503) {
                    assertThat(json(response).path("error").path("code").asText()).isEqualTo("pool_saturated");
                    assertThat(response.header("Retry-After")).isEqualTo("1");
                    rejected++;
                }
            }
            assertThat(ok).isBetween(2, 3);
            assertThat(rejected).isEqualTo(6 - ok);
        }
        assertThat(gateway("/bulk/x").status()).isEqualTo(200);
    }

    @Test
    void weightedRoundRobinFollowsConfiguredWeights() {
        PoolConfig pool = pool("wrr", LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN, null, null, 0, 1_000, 0, 0,
                NEVER_TRIPS, List.of(target("a", A, 3), target("b", B, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(route("wrr", "wrr", RetryConfig.defaults()))));
        Map<String, Integer> served = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            served.merge(upstreamOf(gateway("/wrr/x")), 1, Integer::sum);
        }
        assertThat(served).containsEntry("upstream-a", 30).containsEntry("upstream-b", 10);
    }

    @Test
    void consistentHashPinsEachKeyToOneTarget() {
        PoolConfig pool = pool("chash", LoadBalancingAlgorithm.CONSISTENT_HASH, "header:X-User", null, 0, 1_000, 0, 0,
                NEVER_TRIPS, List.of(target("a", A, 1), target("b", B, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(route("chash", "chash", RetryConfig.defaults()))));
        Map<String, String> owner = new HashMap<>();
        for (int user = 0; user < 30; user++) {
            String id = "user-" + user;
            String first = upstreamOf(gateway("/chash/x", "X-User", id));
            owner.put(id, first);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(upstreamOf(gateway("/chash/x", "X-User", id))).isEqualTo(first);
            }
        }
        assertThat(owner.values().stream().distinct().count()).isEqualTo(2);
    }

    @Test
    void leastConnectionsPrefersTheIdleTarget() throws Exception {
        PoolConfig pool = pool("lc", LoadBalancingAlgorithm.LEAST_CONNECTIONS, null, null, 0, 1_000, 0, 0, NEVER_TRIPS,
                List.of(target("a", A, 1), target("b", B, 1)));
        applyConfig(Configs.document(List.of(pool), List.of(stripRoute("lc", "lc", RetryConfig.defaults()))));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Http.Response> busy = executor.submit(() -> gateway("/lc/slow?ms=1500"));
            Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                    A.hits("/slow") + B.hits("/slow") == 1);
            String busyTarget = A.hits("/slow") == 1 ? "upstream-a" : "upstream-b";
            for (int i = 0; i < 5; i++) {
                assertThat(upstreamOf(gateway("/lc/x"))).isNotEqualTo(busyTarget);
            }
            assertThat(busy.get().status()).isEqualTo(200);
        }
    }
}
