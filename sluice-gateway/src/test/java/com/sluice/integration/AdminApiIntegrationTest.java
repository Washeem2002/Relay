package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class AdminApiIntegrationTest extends GatewayIntegrationTest {

    private static final String POOL = "{\"name\":\"api-pool\",\"allowPrivateTargets\":true,\"targets\":["
            + "{\"id\":\"t1\",\"url\":\"%s\",\"weight\":2}]}";

    private String poolJson() {
        return String.format(POOL, TestEnvironment.UPSTREAM_A.baseUrl());
    }

    @Test
    void poolsSupportFullCrudAndTargetManagement() {
        Http.Response created = admin("POST", "/admin/pools", poolJson());
        assertThat(created.status()).isEqualTo(201);
        assertThat(json(created).path("changed").asBoolean()).isTrue();
        assertThat(json(created).path("configVersion").asLong()).isPositive();
        assertThat(admin("POST", "/admin/pools", poolJson()).status()).isEqualTo(409);

        JsonNode pool = json(admin("GET", "/admin/pools/api-pool", null));
        assertThat(pool.path("targets")).hasSize(1);
        assertThat(pool.path("algorithm").asText()).isEqualTo("WEIGHTED_ROUND_ROBIN");
        assertThat(pool.path("circuitBreaker").path("failureRatePercent").asInt()).isEqualTo(50);

        Http.Response added = admin("POST", "/admin/pools/api-pool/targets",
                "{\"id\":\"t2\",\"url\":\"" + TestEnvironment.UPSTREAM_B.baseUrl() + "\",\"weight\":1}");
        assertThat(added.status()).isEqualTo(201);
        assertThat(admin("POST", "/admin/pools/api-pool/targets",
                "{\"id\":\"t2\",\"url\":\"http://127.0.0.1:9\"}").status()).isEqualTo(409);
        assertThat(json(admin("GET", "/admin/pools/api-pool/targets", null))).hasSize(2);

        Http.Response reweighted = admin("PUT", "/admin/pools/api-pool/targets/t2",
                "{\"id\":\"t2\",\"url\":\"" + TestEnvironment.UPSTREAM_B.baseUrl() + "\",\"weight\":5}");
        assertThat(reweighted.status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/pools/api-pool", null)).path("targets").get(1).path("weight").asInt())
                .isEqualTo(5);
        assertThat(admin("PUT", "/admin/pools/api-pool/targets/t2", "{\"id\":\"other\",\"url\":\"http://127.0.0.1:9\"}")
                .status()).isEqualTo(400);

        assertThat(admin("DELETE", "/admin/pools/api-pool/targets/t2", null).status()).isEqualTo(200);
        assertThat(admin("DELETE", "/admin/pools/api-pool/targets/t2", null).status()).isEqualTo(404);
        assertThat(admin("DELETE", "/admin/pools/api-pool/targets/t1", null).status()).isEqualTo(422);

        String updated = poolJson().replace("WEIGHTED", "WEIGHTED").replace("\"name\":\"api-pool\"",
                "\"name\":\"api-pool\",\"algorithm\":\"LEAST_CONNECTIONS\"");
        assertThat(admin("PUT", "/admin/pools/api-pool", updated).status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/pools/api-pool", null)).path("algorithm").asText())
                .isEqualTo("LEAST_CONNECTIONS");
        assertThat(admin("GET", "/admin/pools/missing", null).status()).isEqualTo(404);
        assertThat(admin("PUT", "/admin/pools/other-name", poolJson()).status()).isEqualTo(400);
        assertThat(admin("DELETE", "/admin/pools/api-pool", null).status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/pools", null))).isEmpty();
    }

    @Test
    void routesPoolsAndReferencesAreValidatedOnEveryWrite() {
        admin("POST", "/admin/pools", poolJson());
        Http.Response orphan = admin("POST", "/admin/routes", "{\"name\":\"r\",\"pathPrefix\":\"/r\",\"pool\":\"nope\"}");
        assertThat(orphan.status()).isEqualTo(422);
        assertThat(orphan.body()).contains("unknown pool nope");

        Http.Response created = admin("POST", "/admin/routes",
                "{\"name\":\"r\",\"pathPrefix\":\"/r\",\"pool\":\"api-pool\",\"methods\":[\"get\",\"post\"],\"host\":\"API.Example.com\"}");
        assertThat(created.status()).isEqualTo(201);
        JsonNode route = json(admin("GET", "/admin/routes/r", null));
        assertThat(route.path("methods")).hasSize(2);
        assertThat(route.path("methods").get(0).asText()).isEqualTo("GET");
        assertThat(route.path("host").asText()).isEqualTo("api.example.com");
        assertThat(route.path("timeoutMs").asInt()).isEqualTo(30_000);

        assertThat(admin("DELETE", "/admin/pools/api-pool", null).status()).isEqualTo(422);
        assertThat(admin("POST", "/admin/routes", "{\"name\":\"dup\",\"pathPrefix\":\"/r\",\"pool\":\"api-pool\","
                + "\"methods\":[\"GET\",\"POST\"],\"host\":\"api.example.com\"}").status()).isEqualTo(422);
        assertThat(admin("POST", "/admin/routes", "{\"name\":\"bad\",\"pathPrefix\":\"x\",\"pool\":\"api-pool\"}")
                .status()).isEqualTo(422);
        assertThat(admin("POST", "/admin/routes", "{\"nme\":\"typo\"}").status()).isEqualTo(400);
        assertThat(admin("PUT", "/admin/routes/r", "{\"name\":\"r\",\"pathPrefix\":\"/renamed\",\"pool\":\"api-pool\"}")
                .status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/routes", null))).hasSize(1);
        assertThat(admin("DELETE", "/admin/routes/r", null).status()).isEqualTo(200);
        assertThat(admin("DELETE", "/admin/routes/r", null).status()).isEqualTo(404);
    }

    @Test
    void plansCachePoliciesAndGlobalLimitCanBeManaged() {
        assertThat(admin("POST", "/admin/plans",
                "{\"name\":\"pro\",\"rateLimit\":{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":100,\"windowSeconds\":10,\"burst\":20}}")
                .status()).isEqualTo(201);
        JsonNode plan = json(admin("GET", "/admin/plans/pro", null));
        assertThat(plan.path("rateLimit").path("burst").asInt()).isEqualTo(20);
        assertThat(admin("POST", "/admin/plans", "{\"name\":\"bad\",\"rateLimit\":{\"limit\":0}}").status()).isEqualTo(422);
        assertThat(admin("PUT", "/admin/plans/pro", "{\"name\":\"pro\",\"rateLimit\":{\"limit\":5,\"windowSeconds\":1}}")
                .status()).isEqualTo(200);
        assertThat(admin("DELETE", "/admin/plans/pro", null).status()).isEqualTo(200);

        assertThat(admin("POST", "/admin/cache-policies", "{\"name\":\"short\",\"ttlSeconds\":5,\"varyHeaders\":[\"Accept\"]}")
                .status()).isEqualTo(201);
        assertThat(json(admin("GET", "/admin/cache-policies/short", null)).path("maxBodyBytes").asLong())
                .isEqualTo(1_048_576);
        assertThat(admin("PUT", "/admin/cache-policies/short", "{\"name\":\"short\",\"ttlSeconds\":9}").status())
                .isEqualTo(200);
        assertThat(admin("DELETE", "/admin/cache-policies/short", null).status()).isEqualTo(200);
        assertThat(admin("GET", "/admin/cache-policies/short", null).status()).isEqualTo(404);

        assertThat(admin("PUT", "/admin/global-limit", "{\"limit\":1000,\"windowSeconds\":1}").status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/global-limit", null)).path("limit").asInt()).isEqualTo(1000);
        assertThat(admin("DELETE", "/admin/global-limit", null).status()).isEqualTo(200);
    }

    @Test
    void tenantsAndApiKeysAreManagedWithoutEverExposingHashes() {
        admin("POST", "/admin/plans", "{\"name\":\"free\",\"rateLimit\":{\"limit\":10,\"windowSeconds\":60}}");
        assertThat(admin("POST", "/admin/tenants", "{\"id\":\"acme\",\"name\":\"Acme\",\"plan\":\"free\"}").status())
                .isEqualTo(201);
        assertThat(admin("POST", "/admin/tenants", "{\"id\":\"ghost\",\"plan\":\"nope\"}").status()).isEqualTo(422);
        Http.Response key = admin("POST", "/admin/tenants/acme/keys", null);
        assertThat(key.status()).isEqualTo(201);
        assertThat(json(key).path("apiKey").asText()).startsWith("sk_");
        assertThat(json(key).path("prefix").asText()).hasSize(11);
        assertThat(json(key).path("note").asText()).contains("not retrievable");
        assertThat(admin("POST", "/admin/tenants/missing/keys", null).status()).isEqualTo(404);

        JsonNode listed = json(admin("GET", "/admin/tenants/acme/keys", null));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).has("hash")).isFalse();
        JsonNode tenant = json(admin("GET", "/admin/tenants/acme", null));
        assertThat(tenant.path("apiKeys").get(0).has("hash")).isFalse();

        Http.Response renamed = admin("PUT", "/admin/tenants/acme", "{\"id\":\"acme\",\"name\":\"Acme Corp\",\"plan\":\"free\"}");
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(json(admin("GET", "/admin/tenants/acme/keys", null))).hasSize(1);
        assertThat(admin("DELETE", "/admin/tenants/acme/keys/key-unknown", null).status()).isEqualTo(404);
        assertThat(admin("DELETE", "/admin/plans/free", null).status()).isEqualTo(422);
        assertThat(admin("DELETE", "/admin/tenants/acme", null).status()).isEqualTo(200);
        assertThat(admin("GET", "/admin/tenants/acme", null).status()).isEqualTo(404);
    }

    @Test
    void auditLogRecordsEveryChangeWithActors() {
        Http.Response created = Http.send("POST", TestEnvironment.adminUrl("/admin/pools"), poolJson(),
                "X-Admin-Token", ADMIN_TOKEN, "X-Admin-Actor", "alice", "Content-Type", "application/json");
        assertThat(created.status()).isEqualTo(201);
        JsonNode entries = json(admin("GET", "/admin/audit?limit=5", null));
        assertThat(entries.get(0).path("action").asText()).isEqualTo("POOL_CREATE");
        assertThat(entries.get(0).path("actor").asText()).isEqualTo("alice");
        assertThat(entries.get(0).path("target").asText()).isEqualTo("pool:api-pool");
        assertThat(json(admin("GET", "/admin/audit?action=POOL_CREATE", null))).isNotEmpty();
        assertThat(json(admin("GET", "/admin/audit?action=NOTHING", null))).isEmpty();
    }

    @Test
    void snapshotDumpShowsCompiledPoolsWithRuntimeState() {
        admin("POST", "/admin/pools", poolJson());
        admin("POST", "/admin/routes", "{\"name\":\"r\",\"pathPrefix\":\"/r\",\"pool\":\"api-pool\"}");
        JsonNode dump = json(admin("GET", "/admin/snapshot", null));
        assertThat(dump.path("version").asLong()).isPositive();
        assertThat(dump.path("routeNames").get(0).asText()).isEqualTo("r");
        JsonNode target = dump.path("pools").get(0).path("targets").get(0);
        assertThat(target.path("health").asText()).isEqualTo("HEALTHY");
        assertThat(target.path("circuit").path("state").asText()).isEqualTo("CLOSED");
        assertThat(target.path("weight").asInt()).isEqualTo(2);
        assertThat(json(admin("GET", "/admin/circuits", null))).hasSize(1);
        assertThat(admin("GET", "/admin/circuits/nope/t1", null).status()).isEqualTo(404);
        assertThat(admin("GET", "/admin/nonexistent", null).status()).isEqualTo(404);
    }
}
