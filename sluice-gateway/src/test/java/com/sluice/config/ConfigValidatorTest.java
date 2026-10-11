package com.sluice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sluice.schema.ApiKeyRecord;
import com.sluice.schema.AuthConfig;
import com.sluice.schema.AuthMode;
import com.sluice.schema.AutoRollbackConfig;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.JwtAlgorithm;
import com.sluice.schema.JwtConfig;
import com.sluice.schema.LoadBalancingAlgorithm;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import com.sluice.schema.TenantConfig;
import com.sluice.support.Configs;
import com.sluice.support.TestProps;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator(TestProps.create());

    private static ConfigDocument valid() {
        return Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:9001")),
                List.of(Configs.route("web", "/web", "web")));
    }

    private List<String> errorsOf(ConfigDocument document) {
        return validator.validate(document);
    }

    @Test
    void acceptsValidConfiguration() {
        assertThat(errorsOf(valid())).isEmpty();
        assertThat(errorsOf(ConfigDocument.empty())).isEmpty();
        validator.validateOrThrow(valid());
    }

    @Test
    void rejectionCarriesAllErrors() {
        ConfigDocument broken = Configs.document(List.of(), List.of(Configs.route("a", "no-slash", "missing")));
        assertThatThrownBy(() -> validator.validateOrThrow(broken))
                .isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).errors()).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void rejectsUnknownPoolReference() {
        ConfigDocument document = Configs.document(List.of(), List.of(Configs.route("web", "/web", "ghost")));
        assertThat(errorsOf(document)).anyMatch(error -> error.contains("unknown pool ghost"));
    }

    @Test
    void rejectsDuplicateRoutesAndPools() {
        ConfigDocument document = Configs.document(
                List.of(Configs.pool("p", "http://127.0.0.1:1"), Configs.pool("p", "http://127.0.0.1:2")),
                List.of(Configs.route("r", "/a", "p"), Configs.route("r", "/b", "p"), Configs.route("other", "/a", "p")));
        List<String> errors = errorsOf(document);
        assertThat(errors).anyMatch(error -> error.contains("duplicate pool name"));
        assertThat(errors).anyMatch(error -> error.contains("duplicate route name"));
        assertThat(errors).anyMatch(error -> error.contains("same host, path prefix and methods"));
    }

    @Test
    void rejectsBadRouteFields() {
        RouteConfig bad = new RouteConfig("bad", "Bad Host!", "/a/../b", Set.of("FETCH"), false, "/x", "web", 999_999,
                new RetryConfig(50, false, 0, 0, 0), AuthConfig.none(), null, "nopolicy", null);
        List<String> errors = errorsOf(Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:1")), List.of(bad)));
        assertThat(errors).anyMatch(error -> error.contains("traversal"));
        assertThat(errors).anyMatch(error -> error.contains("unsupported method FETCH"));
        assertThat(errors).anyMatch(error -> error.contains("host is invalid"));
        assertThat(errors).anyMatch(error -> error.contains("prefixReplacement requires stripPrefix"));
        assertThat(errors).anyMatch(error -> error.contains("timeoutMs"));
        assertThat(errors).anyMatch(error -> error.contains("retry.maxAttempts"));
        assertThat(errors).anyMatch(error -> error.contains("unknown cache policy nopolicy"));
    }

    @Test
    void rejectsInvalidTargets() {
        PoolConfig pool = new PoolConfig("p", LoadBalancingAlgorithm.CONSISTENT_HASH, "weird", List.of(
                new TargetConfig("a", "ftp://host", 1),
                new TargetConfig("a", "http://user:pw@host", 1),
                new TargetConfig("b", "not a url", 1),
                new TargetConfig("c", "http://host/path?x=1", 1),
                new TargetConfig("d", "http://127.0.0.1:1", -5)),
                0, 0, 0, 0, "health", 10, 0, 0, 0, true, CircuitBreakerConfig.defaults());
        List<String> errors = errorsOf(Configs.document(List.of(pool), List.of()));
        assertThat(errors).anyMatch(error -> error.contains("scheme must be http or https"));
        assertThat(errors).anyMatch(error -> error.contains("no credentials"));
        assertThat(errors).anyMatch(error -> error.contains("not a valid uri"));
        assertThat(errors).anyMatch(error -> error.contains("duplicate target id a"));
        assertThat(errors).anyMatch(error -> error.contains("weight"));
        assertThat(errors).anyMatch(error -> error.contains("healthPath must start with /"));
        assertThat(errors).anyMatch(error -> error.contains("healthIntervalMs"));
        assertThat(errors).anyMatch(error -> error.contains("hashKey"));
    }

    @Test
    void rejectsEmptyPoolAndBadCircuitConfig() {
        PoolConfig empty = new PoolConfig("p", null, null, List.of(), 0, 0, 0, 0, null, 0, 0, 0, 0, false,
                new CircuitBreakerConfig(500, 1, 1, 1, 1));
        List<String> errors = errorsOf(Configs.document(List.of(empty), List.of()));
        assertThat(errors).anyMatch(error -> error.contains("at least one target"));
        assertThat(errors).anyMatch(error -> error.contains("failureRatePercent"));
    }

    @Test
    void blocksPrivateTargetsUnlessExplicitlyAllowed() {
        PoolConfig blocked = new PoolConfig("p", null, null, List.of(new TargetConfig("a", "http://127.0.0.1:80", 1),
                new TargetConfig("b", "http://10.1.2.3:80", 1), new TargetConfig("c", "http://169.254.169.254", 1),
                new TargetConfig("d", "http://[::1]:80", 1)),
                0, 0, 0, 0, null, 0, 0, 0, 0, false, CircuitBreakerConfig.defaults());
        List<String> errors = errorsOf(Configs.document(List.of(blocked), List.of()));
        assertThat(errors).filteredOn(error -> error.contains("private or reserved")).hasSize(4);
        PoolConfig allowed = new PoolConfig("p", null, null, blocked.targets(), 0, 0, 0, 0, null, 0, 0, 0, 0, true,
                CircuitBreakerConfig.defaults());
        assertThat(errorsOf(Configs.document(List.of(allowed), List.of()))).isEmpty();
    }

    @Test
    void validatesCanaryRules() {
        CanaryConfig bad = new CanaryConfig("web", 150, "weird", new AutoRollbackConfig(500, 0, 0));
        RouteConfig route = Configs.route("r", "/r", "web", false, 0, RetryConfig.defaults(), null, bad);
        List<String> errors = errorsOf(Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:1")), List.of(route)));
        assertThat(errors).anyMatch(error -> error.contains("canary pool must differ"));
        assertThat(errors).anyMatch(error -> error.contains("canary.weight"));
        assertThat(errors).anyMatch(error -> error.contains("stickyKey"));
        assertThat(errors).anyMatch(error -> error.contains("errorRatePercent"));
        RouteConfig missing = Configs.route("m", "/m", "web", false, 0, RetryConfig.defaults(), null,
                new CanaryConfig("nope", 10, null, null));
        assertThat(errorsOf(Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:1")), List.of(missing))))
                .anyMatch(error -> error.contains("unknown canary pool"));
    }

    @Test
    void validatesJwtAuthRequirements() {
        RouteConfig noJwt = new RouteConfig("a", null, "/a", Set.of(), false, null, "web", 0, null,
                new AuthConfig(AuthMode.JWT, null), null, null, null);
        RouteConfig hsNoSecret = new RouteConfig("b", null, "/b", Set.of(), false, null, "web", 0, null,
                new AuthConfig(AuthMode.JWT, new JwtConfig(JwtAlgorithm.HS256, null, null, null, null, null)),
                null, null, null);
        RouteConfig rsNoJwks = new RouteConfig("c", null, "/c", Set.of(), false, null, "web", 0, null,
                new AuthConfig(AuthMode.JWT, new JwtConfig(JwtAlgorithm.RS256, null, null, null, null, null)),
                null, null, null);
        List<String> errors = errorsOf(Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:1")),
                List.of(noJwt, hsNoSecret, rsNoJwks)));
        assertThat(errors).anyMatch(error -> error.contains("auth.jwt is required"));
        assertThat(errors).anyMatch(error -> error.contains("secretEnv is required"));
        assertThat(errors).anyMatch(error -> error.contains("jwksPath is required"));
    }

    @Test
    void validatesPlansTenantsCachePoliciesAndGlobalLimit() {
        RateLimitPolicy bad = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 0, -1, -1);
        ConfigDocument document = new ConfigDocument(List.of(), List.of(),
                List.of(new PlanConfig("free", bad), new PlanConfig("free", null)),
                List.of(new TenantConfig("t1", "T1", "ghost-plan", false,
                                List.of(new ApiKeyRecord("k1", "h", "p", Instant.EPOCH, false))),
                        new TenantConfig("t2", "T2", null, false,
                                List.of(new ApiKeyRecord("k2", "h", "p", Instant.EPOCH, false)))),
                List.of(new CachePolicyConfig("c", -1, -1, 1L << 40, List.of("bad header!"), false)),
                bad);
        List<String> errors = errorsOf(document);
        assertThat(errors).anyMatch(error -> error.contains("plan free: limit must be at least 1"));
        assertThat(errors).anyMatch(error -> error.contains("plan free: duplicate name"));
        assertThat(errors).anyMatch(error -> error.contains("rateLimit is required"));
        assertThat(errors).anyMatch(error -> error.contains("unknown plan ghost-plan"));
        assertThat(errors).anyMatch(error -> error.contains("hash is already used"));
        assertThat(errors).anyMatch(error -> error.contains("ttlSeconds"));
        assertThat(errors).anyMatch(error -> error.contains("maxBodyBytes"));
        assertThat(errors).anyMatch(error -> error.contains("invalid vary header"));
        assertThat(errors).anyMatch(error -> error.contains("globalLimit"));
    }

    @Test
    void rejectsInvalidNames() {
        ConfigDocument document = Configs.document(List.of(Configs.pool("bad name", "http://127.0.0.1:1")),
                List.of(Configs.route("bad|route", "/a", "x")));
        List<String> errors = errorsOf(document);
        assertThat(errors).anyMatch(error -> error.contains("pool name is invalid"));
        assertThat(errors).anyMatch(error -> error.contains("route name is invalid"));
    }
}
