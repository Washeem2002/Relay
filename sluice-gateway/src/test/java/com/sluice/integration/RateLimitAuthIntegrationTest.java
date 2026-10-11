package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sluice.schema.AuthConfig;
import com.sluice.schema.AuthMode;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.JwtAlgorithm;
import com.sluice.schema.JwtConfig;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TenantConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RateLimitAuthIntegrationTest extends GatewayIntegrationTest {

    private static final String JWT_SECRET = "jwt-test-secret-that-is-longer-than-32-bytes";

    private final PoolConfig pool = Configs.pool("rl-pool", TestEnvironment.UPSTREAM_A.baseUrl());

    private RouteConfig apiKeyRoute(String name, RateLimitPolicy routePolicy) {
        return new RouteConfig(name, null, "/" + name, Set.of(), true, null, "rl-pool", 0, RetryConfig.defaults(),
                new AuthConfig(AuthMode.API_KEY, null), routePolicy, null, null);
    }

    private String provision(ConfigDocument document, String tenantId) {
        applyConfig(document);
        Http.Response created = admin("POST", "/admin/tenants/" + tenantId + "/keys", null);
        assertThat(created.status()).isEqualTo(201);
        return json(created).path("apiKey").asText();
    }

    private ConfigDocument documentWith(RouteConfig route, RateLimitPolicy planPolicy, RateLimitPolicy global,
                                        String... tenantIds) {
        List<TenantConfig> tenants = new java.util.ArrayList<>();
        for (String id : tenantIds) {
            tenants.add(new TenantConfig(id, id, "plan", false, null));
        }
        return new ConfigDocument(List.of(pool), List.of(route), List.of(new PlanConfig("plan", planPolicy)), tenants,
                List.of(), global);
    }

    @Test
    void slidingWindowRejectsWithStandardHeaders() {
        String key = provision(documentWith(apiKeyRoute("sw", null),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 3, 60, 0), null, "acme"), "acme");
        for (int i = 0; i < 3; i++) {
            Http.Response ok = gateway("/sw/ping", "X-API-Key", key);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.header("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(ok.header("X-RateLimit-Remaining")).isEqualTo(String.valueOf(2 - i));
            assertThat(Long.parseLong(ok.header("X-RateLimit-Reset"))).isGreaterThan(System.currentTimeMillis() / 1000 - 2);
        }
        Http.Response limited = gateway("/sw/ping", "X-API-Key", key);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(json(limited).path("error").path("code").asText()).isEqualTo("rate_limited");
        assertThat(limited.header("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(limited.header("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(limited.header("X-RateLimit-Reset")).isNotNull();
        assertThat(TestEnvironment.UPSTREAM_A.totalHits()).isEqualTo(3);
    }

    @Test
    void tokenBucketAllowsBurstThenRejects() {
        String key = provision(documentWith(apiKeyRoute("tb", null),
                new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 60, 60, 2), null, "acme"), "acme");
        assertThat(gateway("/tb/x", "X-API-Key", key).status()).isEqualTo(200);
        Http.Response second = gateway("/tb/x", "X-API-Key", key);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.header("X-RateLimit-Limit")).isEqualTo("2");
        Http.Response third = gateway("/tb/x", "X-API-Key", key);
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.header("Retry-After")).isEqualTo("1");
    }

    @Test
    void tenantsAreIsolatedFromEachOther() {
        ConfigDocument document = documentWith(apiKeyRoute("iso", null),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 2, 60, 0), null, "alpha", "beta");
        applyConfig(document);
        String alpha = json(admin("POST", "/admin/tenants/alpha/keys", null)).path("apiKey").asText();
        String beta = json(admin("POST", "/admin/tenants/beta/keys", null)).path("apiKey").asText();
        gateway("/iso/x", "X-API-Key", alpha);
        gateway("/iso/x", "X-API-Key", alpha);
        assertThat(gateway("/iso/x", "X-API-Key", alpha).status()).isEqualTo(429);
        assertThat(gateway("/iso/x", "X-API-Key", beta).status()).isEqualTo(200);
    }

    @Test
    void perTenantPerRouteLimitApplies() {
        RateLimitPolicy routePolicy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 2, 60, 0);
        String key = provision(documentWith(apiKeyRoute("tr", routePolicy),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 100, 60, 0), null, "acme"), "acme");
        assertThat(gateway("/tr/x", "X-API-Key", key).status()).isEqualTo(200);
        assertThat(gateway("/tr/x", "X-API-Key", key).status()).isEqualTo(200);
        Http.Response limited = gateway("/tr/x", "X-API-Key", key);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(json(limited).path("error").path("message").asText()).contains("tenant-route");
    }

    @Test
    void globalLimitAppliesAcrossAnonymousCallers() {
        RouteConfig open = Configs.route("open", "/open", "rl-pool");
        applyConfig(new ConfigDocument(List.of(pool), List.of(open), List.of(), List.of(), List.of(),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 4, 60, 0)));
        for (int i = 0; i < 4; i++) {
            assertThat(gateway("/open/x").status()).isEqualTo(200);
        }
        Http.Response limited = gateway("/open/x");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(json(limited).path("error").path("message").asText()).contains("global");
    }

    @Test
    void apiKeyAuthenticationRejectsMissingInvalidAndRevokedKeys() {
        String key = provision(documentWith(apiKeyRoute("auth", null),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 100, 60, 0), null, "acme"), "acme");
        Http.Response missing = gateway("/auth/x");
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.header("WWW-Authenticate")).isEqualTo("ApiKey");
        assertThat(json(missing).path("error").path("code").asText()).isEqualTo("missing_api_key");
        assertThat(gateway("/auth/x", "X-API-Key", "sk_bogus").status()).isEqualTo(401);

        Http.Response ok = gateway("/auth/x", "X-API-Key", key);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(json(ok).path("headers").has("x-api-key")).isFalse();
        assertThat(json(ok).path("headers").path("x-tenant-id").asText()).isEqualTo("acme");

        String keyId = json(admin("GET", "/admin/tenants/acme/keys", null)).get(0).path("id").asText();
        assertThat(admin("DELETE", "/admin/tenants/acme/keys/" + keyId, null).status()).isEqualTo(200);
        assertThat(gateway("/auth/x", "X-API-Key", key).status()).isEqualTo(401);
    }

    @Test
    void apiKeysAreStoredHashedAndShownOnce() {
        String key = provision(documentWith(apiKeyRoute("hash", null),
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 100, 60, 0), null, "acme"), "acme");
        String stored = admin("GET", "/admin/config", null).body();
        assertThat(stored).doesNotContain(key);
        assertThat(admin("GET", "/admin/tenants/acme", null).body()).doesNotContain(key).doesNotContain("\"hash\"");
        assertThat(admin("GET", "/admin/tenants/acme/keys", null).body()).doesNotContain(key);
    }

    @Test
    void hs256JwtRouteAcceptsValidAndRejectsExpiredOrMissingTokens() throws Exception {
        JwtConfig jwt = new JwtConfig(JwtAlgorithm.HS256, "SLUICE_TEST_JWT_SECRET", null, "sluice-tests", null, null);
        RouteConfig route = new RouteConfig("jwt", null, "/jwt", Set.of(), true, null, "rl-pool", 0, null,
                new AuthConfig(AuthMode.JWT, jwt), null, null, null);
        applyConfig(new ConfigDocument(List.of(pool), List.of(route),
                List.of(new PlanConfig("plan", new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 1, 60, 0))),
                List.of(new TenantConfig("acme", "Acme", "plan", false, null)), List.of(), null));

        String valid = hsToken(new Date(System.currentTimeMillis() + 60_000), "sluice-tests");
        Http.Response ok = gateway("/jwt/x", "Authorization", "Bearer " + valid);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(json(ok).path("headers").path("x-tenant-id").asText()).isEqualTo("acme");
        assertThat(gateway("/jwt/x", "Authorization", "Bearer " + valid).status()).isEqualTo(429);

        assertThat(gateway("/jwt/x").status()).isEqualTo(401);
        assertThat(gateway("/jwt/x", "Authorization", "Bearer " + hsToken(new Date(System.currentTimeMillis() - 600_000),
                "sluice-tests")).status()).isEqualTo(401);
        assertThat(gateway("/jwt/x", "Authorization", "Bearer " + hsToken(new Date(System.currentTimeMillis() + 60_000),
                "other-issuer")).status()).isEqualTo(401);
        Http.Response bad = gateway("/jwt/x", "Authorization", "Bearer garbage");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.header("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void rs256JwtRouteUsesStaticJwksFile(@TempDir Path directory) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        Path jwks = directory.resolve("jwks.json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString(), StandardCharsets.UTF_8);
        JwtConfig jwt = new JwtConfig(JwtAlgorithm.RS256, null, jwks.toString(), null, null, null);
        RouteConfig route = new RouteConfig("rs", null, "/rs", Set.of(), true, null, "rl-pool", 0, null,
                new AuthConfig(AuthMode.JWT, jwt), null, null, null);
        applyConfig(Configs.document(List.of(pool), List.of(route)));

        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1").build(),
                new JWTClaimsSet.Builder().subject("rs-user").expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        token.sign(new RSASSASigner(key));
        assertThat(gateway("/rs/x", "Authorization", "Bearer " + token.serialize()).status()).isEqualTo(200);

        RSAKey other = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1").build(),
                new JWTClaimsSet.Builder().subject("rs-user").expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        forged.sign(new RSASSASigner(other));
        assertThat(gateway("/rs/x", "Authorization", "Bearer " + forged.serialize()).status()).isEqualTo(401);
    }

    @Test
    void jwtConfigWithMissingSecretEnvironmentIsRejectedAtConfigTime() {
        JwtConfig jwt = new JwtConfig(JwtAlgorithm.HS256, "SLUICE_ENV_THAT_DOES_NOT_EXIST", null, null, null, null);
        RouteConfig route = new RouteConfig("nojwt", null, "/nojwt", Set.of(), false, null, "rl-pool", 0, null,
                new AuthConfig(AuthMode.JWT, jwt), null, null, null);
        Http.Response response = putConfig(Configs.document(List.of(pool), List.of(route)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("SLUICE_ENV_THAT_DOES_NOT_EXIST");
    }

    private static String hsToken(Date expiry, String issuer) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject("user-1").issuer(issuer).claim("tenant", "acme").expirationTime(expiry).build());
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
