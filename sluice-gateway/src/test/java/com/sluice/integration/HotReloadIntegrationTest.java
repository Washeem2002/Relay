package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sluice.config.ConfigReloader;
import com.sluice.model.ConfigVersionEntity;
import com.sluice.repository.ConfigVersionRepository;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RetryConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HotReloadIntegrationTest extends GatewayIntegrationTest {

    @Autowired
    private ConfigVersionRepository versions;

    @Autowired
    private ConfigReloader reloader;

    private ConfigDocument single(String routeName, String pool, String url) {
        return Configs.document(List.of(Configs.pool(pool, url)),
                List.of(Configs.route(routeName, "/" + routeName, pool, true, 0, RetryConfig.defaults(), null, null)));
    }

    private long activeVersion() {
        return json(admin("GET", "/admin/snapshot", null)).path("version").asLong();
    }

    @Test
    void swapsRoutesWithoutFailingRequestsThatAreAlreadyInFlight() throws Exception {
        applyConfig(single("swap", "old-pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        CompletableFuture<Http.Response> inFlight = CompletableFuture.supplyAsync(() -> gateway("/swap/slow?ms=1500"));
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> TestEnvironment.UPSTREAM_A.hits("/slow") == 1);

        long before = activeVersion();
        applyConfig(single("swap", "new-pool", TestEnvironment.UPSTREAM_B.baseUrl()));
        assertThat(activeVersion()).isGreaterThan(before);

        Http.Response afterSwap = gateway("/swap/ping");
        assertThat(afterSwap.status()).isEqualTo(200);
        assertThat(upstreamOf(afterSwap)).isEqualTo("upstream-b");

        Http.Response finished = inFlight.get();
        assertThat(finished.status()).isEqualTo(200);
        assertThat(upstreamOf(finished)).isEqualTo("upstream-a");
    }

    @Test
    void addsAndRemovesRoutesLive() {
        applyConfig(single("first", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        assertThat(gateway("/first/x").status()).isEqualTo(200);
        assertThat(gateway("/second/x").status()).isEqualTo(404);
        Http.Response created = admin("POST", "/admin/routes",
                "{\"name\":\"second\",\"pathPrefix\":\"/second\",\"pool\":\"pool\",\"stripPrefix\":true}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(gateway("/second/x").status()).isEqualTo(200);
        assertThat(admin("DELETE", "/admin/routes/first", null).status()).isEqualTo(200);
        assertThat(gateway("/first/x").status()).isEqualTo(404);
        assertThat(gateway("/second/x").status()).isEqualTo(200);
    }

    @Test
    void invalidConfigIsRejectedAndTheLastKnownGoodSnapshotKeepsServing() {
        applyConfig(single("good", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        long version = activeVersion();
        int versionCount = json(admin("GET", "/admin/config/versions", null)).size();

        Http.Response rejected = putConfig(Configs.document(List.of(Configs.pool("pool", TestEnvironment.UPSTREAM_A.baseUrl())),
                List.of(Configs.route("good", "/good", "pool"), Configs.route("broken", "/broken", "ghost-pool"))));
        assertThat(rejected.status()).isEqualTo(422);
        JsonNode errors = json(rejected).path("error").path("errors");
        assertThat(errors.toString()).contains("unknown pool ghost-pool");

        assertThat(activeVersion()).isEqualTo(version);
        assertThat(json(admin("GET", "/admin/config/versions", null)).size()).isEqualTo(versionCount);
        assertThat(gateway("/good/x").status()).isEqualTo(200);
        assertThat(gateway("/broken/x").status()).isEqualTo(404);

        Http.Response malformed = admin("PUT", "/admin/config", "{\"routes\":[{\"name\":\"x\",\"surprise\":true}]}");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(activeVersion()).isEqualTo(version);
    }

    @Test
    void validationEndpointReportsErrorsWithoutChangingAnything() {
        applyConfig(single("keep", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        long version = activeVersion();
        Http.Response invalid = admin("POST", "/admin/config/validate",
                Configs.document(List.of(), List.of(Configs.route("r", "/r", "none"))));
        assertThat(invalid.status()).isEqualTo(200);
        assertThat(json(invalid).path("valid").asBoolean()).isFalse();
        assertThat(json(invalid).path("errors")).isNotEmpty();
        Http.Response valid = admin("POST", "/admin/config/validate", ConfigDocument.empty());
        assertThat(json(valid).path("valid").asBoolean()).isTrue();
        assertThat(activeVersion()).isEqualTo(version);
    }

    @Test
    void everyChangeCreatesAVersionAndAnyVersionCanBeRolledBackTo() {
        applyConfig(single("one", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        long versionWithOne = activeVersion();
        applyConfig(single("two", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        assertThat(gateway("/one/x").status()).isEqualTo(404);
        assertThat(gateway("/two/x").status()).isEqualTo(200);

        Http.Response rollback = admin("POST", "/admin/config/rollback/" + versionWithOne, null);
        assertThat(rollback.status()).isEqualTo(200);
        assertThat(json(rollback).path("restoredFrom").asLong()).isEqualTo(versionWithOne);
        assertThat(gateway("/one/x").status()).isEqualTo(200);
        assertThat(gateway("/two/x").status()).isEqualTo(404);

        JsonNode history = json(admin("GET", "/admin/config/versions", null));
        assertThat(history.get(0).path("action").asText()).isEqualTo("ROLLBACK");
        assertThat(history.get(0).path("version").asLong()).isGreaterThan(versionWithOne + 1);
        JsonNode old = json(admin("GET", "/admin/config/versions/" + versionWithOne, null));
        assertThat(old.path("document").path("routes").get(0).path("name").asText()).isEqualTo("one");
        assertThat(admin("GET", "/admin/config/versions/999999", null).status()).isEqualTo(404);
        assertThat(admin("POST", "/admin/config/rollback/999999", null).status()).isEqualTo(404);
        assertThat(admin("GET", "/admin/audit?action=ROLLBACK", null).body()).contains("ROLLBACK");
    }

    @Test
    void peersPickUpChangesThroughRedisPubSubAndThePollingSafetyNet() throws Exception {
        applyConfig(single("local", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        long current = activeVersion();

        String remoteDocument = mapper.writeValueAsString(single("remote", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        versions.saveAndFlush(new ConfigVersionEntity(current + 1, remoteDocument, Instant.now(), "other-instance",
                "ROUTE_CREATE", "written by a peer"));
        redis.convertAndSend("sluice:config:updates", String.valueOf(current + 1));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> activeVersion() == current + 1);
        assertThat(gateway("/remote/x").status()).isEqualTo(200);
        assertThat(gateway("/local/x").status()).isEqualTo(404);

        String polledDocument = mapper.writeValueAsString(single("polled", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        versions.saveAndFlush(new ConfigVersionEntity(current + 2, polledDocument, Instant.now(), "other-instance",
                "ROUTE_CREATE", "written by a peer without a notification"));
        assertThat(activeVersion()).isEqualTo(current + 1);
        reloader.poll();
        assertThat(activeVersion()).isEqualTo(current + 2);
        assertThat(gateway("/polled/x").status()).isEqualTo(200);
        assertThat(reloader.reloadLatest()).isEqualTo(ConfigReloader.Outcome.UNCHANGED);
        assertThat(json(admin("POST", "/admin/config/reload", null)).path("outcome").asText()).isEqualTo("UNCHANGED");
    }

    @Test
    void aStoredVersionThatCannotBeCompiledNeverReplacesTheWorkingSnapshot() throws Exception {
        applyConfig(single("stable", "pool", TestEnvironment.UPSTREAM_A.baseUrl()));
        long current = activeVersion();
        String broken = mapper.writeValueAsString(Configs.document(List.of(),
                List.of(Configs.route("bad", "/bad", "missing-pool"))));
        versions.saveAndFlush(new ConfigVersionEntity(current + 1, broken, Instant.now(), "other-instance",
                "ROUTE_CREATE", "corrupt"));
        assertThat(reloader.reloadLatest()).isEqualTo(ConfigReloader.Outcome.FAILED);
        assertThat(activeVersion()).isEqualTo(current);
        assertThat(gateway("/stable/x").status()).isEqualTo(200);
        assertThat(reloader.lastError()).contains("missing-pool");
        assertThat(Http.get(TestEnvironment.adminUrl("/metrics")).body()).contains("sluice_config_reload_failures_total");
    }
}
