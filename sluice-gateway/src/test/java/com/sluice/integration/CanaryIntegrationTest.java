package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.AutoRollbackConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class CanaryIntegrationTest extends GatewayIntegrationTest {

    private void configure(String routeName, CanaryConfig canary) {
        RouteConfig route = Configs.route(routeName, "/" + routeName, "stable", true, 0,
                new RetryConfig(1, false, 0, 0, 0), null, canary);
        applyConfig(Configs.document(List.of(Configs.pool("stable", TestEnvironment.UPSTREAM_A.baseUrl()),
                Configs.pool("canary", TestEnvironment.UPSTREAM_B.baseUrl())), List.of(route)));
    }

    private int canaryWeight(String routeName) {
        return json(admin("GET", "/admin/routes/" + routeName, null)).path("canary").path("weight").asInt();
    }

    @Test
    void splitsTrafficByWeightAndKeepsEachClientSticky() {
        configure("shop", new CanaryConfig("canary", 30, "header:X-User", null));
        int canaryHits = 0;
        int total = 400;
        Map<String, String> firstSeen = new HashMap<>();
        for (int user = 0; user < total; user++) {
            String id = "user-" + user;
            String upstream = upstreamOf(gateway("/shop/x", "X-User", id));
            firstSeen.put(id, upstream);
            if (upstream.equals("upstream-b")) {
                canaryHits++;
            }
        }
        double ratio = (double) canaryHits / total;
        assertThat(ratio).isBetween(0.20, 0.40);
        for (int user = 0; user < 40; user++) {
            String id = "user-" + user;
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(upstreamOf(gateway("/shop/x", "X-User", id))).isEqualTo(firstSeen.get(id));
            }
        }
    }

    @Test
    void headerOverrideForcesTheBucket() {
        configure("shop", new CanaryConfig("canary", 0, "header:X-User", null));
        assertThat(upstreamOf(gateway("/shop/x", "X-User", "someone"))).isEqualTo("upstream-a");
        assertThat(upstreamOf(gateway("/shop/x", "X-User", "someone", "X-Canary", "always"))).isEqualTo("upstream-b");
        configure("shop", new CanaryConfig("canary", 100, "header:X-User", null));
        assertThat(upstreamOf(gateway("/shop/x", "X-User", "someone"))).isEqualTo("upstream-b");
        assertThat(upstreamOf(gateway("/shop/x", "X-User", "someone", "X-Canary", "never"))).isEqualTo("upstream-a");
    }

    @Test
    void weightCanBeShiftedThroughTheAdminApi() {
        configure("shop", new CanaryConfig("canary", 0, "header:X-User", null));
        Http.Response all = admin("PATCH", "/admin/routes/shop/canary", "{\"weight\":100}");
        assertThat(all.status()).isEqualTo(200);
        for (int i = 0; i < 10; i++) {
            assertThat(upstreamOf(gateway("/shop/x", "X-User", "u" + i))).isEqualTo("upstream-b");
        }
        admin("PATCH", "/admin/routes/shop/canary", "{\"weight\":0}");
        for (int i = 0; i < 10; i++) {
            assertThat(upstreamOf(gateway("/shop/x", "X-User", "u" + i))).isEqualTo("upstream-a");
        }
        assertThat(admin("DELETE", "/admin/routes/shop/canary", null).status()).isEqualTo(200);
        assertThat(admin("GET", "/admin/routes/shop", null).body()).doesNotContain("canary");
        assertThat(admin("PATCH", "/admin/routes/shop/canary", "{\"weight\":10}").status()).isEqualTo(404);
    }

    @Test
    void failingCanaryIsRolledBackAutomaticallyAndAudited() {
        configure("shop", new CanaryConfig("canary", 100, "header:X-User", new AutoRollbackConfig(50, 60, 10)));
        TestEnvironment.UPSTREAM_B.setFailing(true);
        for (int i = 0; i < 12; i++) {
            assertThat(gateway("/shop/x", "X-User", "u" + i).status()).isEqualTo(503);
        }
        Awaitility.await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(canaryWeight("shop")).isZero());
        for (int i = 0; i < 5; i++) {
            Http.Response response = gateway("/shop/x", "X-User", "u" + i);
            assertThat(response.status()).isEqualTo(200);
            assertThat(upstreamOf(response)).isEqualTo("upstream-a");
        }
        Http.Response audit = admin("GET", "/admin/audit?action=CANARY_ROLLBACK", null);
        assertThat(json(audit)).hasSize(1);
        assertThat(json(audit).get(0).path("actor").asText()).isEqualTo("sluice-canary-monitor");
        assertThat(json(audit).get(0).path("target").asText()).isEqualTo("route:shop");
        assertThat(json(audit).get(0).path("detail").asText()).contains("error rate");
        assertThat(json(admin("GET", "/admin/config/versions?limit=1", null)).get(0).path("action").asText())
                .isEqualTo("CANARY_ROLLBACK");
        assertThat(Http.get(TestEnvironment.adminUrl("/metrics")).body()).contains("sluice_canary_rollbacks_total");
    }

    @Test
    void canaryIsNotRolledBackWithoutEnoughTraffic() throws InterruptedException {
        configure("shop", new CanaryConfig("canary", 100, "header:X-User", new AutoRollbackConfig(50, 60, 10)));
        TestEnvironment.UPSTREAM_B.setFailing(true);
        for (int i = 0; i < 5; i++) {
            gateway("/shop/x", "X-User", "u" + i);
        }
        Thread.sleep(1_200);
        assertThat(canaryWeight("shop")).isEqualTo(100);
    }

    @Test
    void healthyCanarySurvivesEvaluation() throws InterruptedException {
        configure("shop", new CanaryConfig("canary", 100, "header:X-User", new AutoRollbackConfig(50, 60, 10)));
        for (int i = 0; i < 30; i++) {
            assertThat(gateway("/shop/x", "X-User", "u" + i).status()).isEqualTo(200);
        }
        Thread.sleep(1_200);
        assertThat(canaryWeight("shop")).isEqualTo(100);
        ConfigDocument current = mapperRead(admin("GET", "/admin/config", null));
        assertThat(current.routes()).hasSize(1);
    }

    private ConfigDocument mapperRead(Http.Response response) {
        try {
            return mapper.treeToValue(json(response).path("document"), ConfigDocument.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
