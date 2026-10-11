package com.sluice.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sluice.balancing.PoolRuntimeRegistry;
import com.sluice.balancing.TargetStateRegistry;
import com.sluice.config.ConfigValidationException;
import com.sluice.config.ConfigValidator;
import com.sluice.observability.SluiceMetrics;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.support.Configs;
import com.sluice.support.MutableClock;
import com.sluice.support.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnapshotCompilerTest {

    private PoolRuntimeRegistry runtimes;
    private TargetStateRegistry states;
    private SnapshotCompiler compiler;

    @BeforeEach
    void setUp() {
        MutableClock clock = new MutableClock();
        states = new TargetStateRegistry(clock, new SluiceMetrics(new SimpleMeterRegistry()));
        runtimes = new PoolRuntimeRegistry(TestProps.create());
        compiler = new SnapshotCompiler(new ConfigValidator(TestProps.create()), states, runtimes, clock);
    }

    @AfterEach
    void tearDown() {
        runtimes.shutdown();
    }

    private static ConfigDocument document() {
        return Configs.document(
                List.of(Configs.pool("web", "http://127.0.0.1:9001", "http://127.0.0.1:9002"),
                        Configs.pool("web-canary", "http://127.0.0.1:9003")),
                List.of(Configs.route("web", "/web", "web", true, 5_000, RetryConfig.defaults(), null,
                        new CanaryConfig("web-canary", 10, null, null))));
    }

    @Test
    void compilesPoolsRoutesAndMatcher() {
        RoutingSnapshot snapshot = compiler.compile(7, document());
        assertThat(snapshot.version()).isEqualTo(7);
        assertThat(snapshot.pools()).containsKeys("web", "web-canary");
        assertThat(snapshot.pools().get("web").targets()).hasSize(2);
        CompiledRoute route = snapshot.match("any", "/web/items", "GET").orElseThrow();
        assertThat(route.stable().name()).isEqualTo("web");
        assertThat(route.canary().name()).isEqualTo("web-canary");
        assertThat(snapshot.match("any", "/other", "GET")).isEmpty();
    }

    @Test
    void sharedStateAndRuntimeSurviveRecompilation() {
        RoutingSnapshot first = compiler.compile(1, document());
        RoutingSnapshot second = compiler.compile(2, document());
        assertThat(second.pools().get("web").targets().get(0).state())
                .isSameAs(first.pools().get("web").targets().get(0).state());
        assertThat(second.pools().get("web").runtime()).isSameAs(first.pools().get("web").runtime());
        assertThat(second.routes().get("web").retryBudget()).isSameAs(first.routes().get("web").retryBudget());
    }

    @Test
    void invalidDocumentIsRejectedWithoutSideEffects() {
        ConfigDocument broken = Configs.document(List.of(), List.of(Configs.route("web", "/web", "ghost")));
        assertThatThrownBy(() -> compiler.compile(1, broken)).isInstanceOf(ConfigValidationException.class);
        assertThat(states.all()).isEmpty();
    }

    @Test
    void pruneDropsStateOfRemovedTargets() {
        RoutingSnapshot first = compiler.compile(1, document());
        assertThat(states.all()).hasSize(3);
        ConfigDocument reduced = Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:9001")),
                List.of(Configs.route("web", "/web", "web")));
        RoutingSnapshot second = compiler.compile(2, reduced);
        compiler.prune(second);
        assertThat(states.all()).hasSize(1);
        assertThat(first.pools()).hasSize(2);
    }

    @Test
    void rewritesPathsAccordingToStripPrefixAndReplacement() {
        RouteConfig strip = Configs.route("a", "/api/v1", "web", true, 0, RetryConfig.defaults(), null, null);
        RouteConfig replace = new RouteConfig("b", null, "/old", Set.of(), true, "/new", "web", 0, null, null, null,
                null, null);
        RouteConfig keep = Configs.route("c", "/keep", "web");
        RouteConfig root = Configs.route("d", "/", "web", true, 0, RetryConfig.defaults(), null, null);
        ConfigDocument document = Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:9001")),
                List.of(strip, replace, keep, root));
        RoutingSnapshot snapshot = compiler.compile(1, document);
        assertThat(snapshot.routes().get("a").rewritePath("/api/v1/users/42")).isEqualTo("/users/42");
        assertThat(snapshot.routes().get("a").rewritePath("/api/v1")).isEqualTo("/");
        assertThat(snapshot.routes().get("a").rewritePath("//api//v1/x")).isEqualTo("/x");
        assertThat(snapshot.routes().get("b").rewritePath("/old/thing")).isEqualTo("/new/thing");
        assertThat(snapshot.routes().get("b").rewritePath("/old")).isEqualTo("/new");
        assertThat(snapshot.routes().get("c").rewritePath("/keep/x")).isEqualTo("/keep/x");
        assertThat(snapshot.routes().get("d").rewritePath("/anything")).isEqualTo("/anything");
    }

    @Test
    void emptySnapshotMatchesNothing() {
        assertThat(RoutingSnapshot.empty().match("h", "/", "GET")).isEmpty();
        assertThat(new SnapshotHolder().version()).isZero();
    }

    @Test
    void jwtRouteWithMissingSecretFailsCompilation() {
        RouteConfig jwtRoute = new RouteConfig("j", null, "/j", Set.of(), false, null, "web", 0, null,
                new com.sluice.schema.AuthConfig(com.sluice.schema.AuthMode.JWT,
                        new com.sluice.schema.JwtConfig(com.sluice.schema.JwtAlgorithm.HS256,
                                "SLUICE_TEST_UNSET_SECRET_VARIABLE", null, null, null, null)),
                null, null, null);
        ConfigDocument document = Configs.document(List.of(Configs.pool("web", "http://127.0.0.1:9001")),
                List.of(jwtRoute));
        assertThatThrownBy(() -> compiler.compile(1, document))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("SLUICE_TEST_UNSET_SECRET_VARIABLE");
    }
}
