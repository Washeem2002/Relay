package com.sluice.canary;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.observability.SluiceMetrics;
import com.sluice.resilience.RetryBudget;
import com.sluice.resilience.RetryPolicy;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.RouteMatcher;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotHolder;
import com.sluice.auth.TenantDirectory;
import com.sluice.schema.AutoRollbackConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.support.Configs;
import com.sluice.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanaryTest {

    private final CanarySplitter splitter = new CanarySplitter();

    private static CanaryConfig canary(int weight, String stickyKey) {
        return new CanaryConfig("canary", weight, stickyKey, null);
    }

    @Test
    void sameClientAlwaysLandsInTheSameBucket() {
        CanaryConfig config = canary(30, "api_key");
        for (int i = 0; i < 500; i++) {
            String key = "key-" + i;
            boolean first = splitter.decide("r", config, name -> name.equals("X-API-Key") ? key : null, n -> null, "1.1.1.1").useCanary();
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(splitter.decide("r", config, name -> name.equals("X-API-Key") ? key : null, n -> null, "9.9.9.9")
                        .useCanary()).isEqualTo(first);
            }
        }
    }

    @Test
    void distributionMatchesConfiguredWeight() {
        CanaryConfig config = canary(20, "api_key");
        int canaryHits = 0;
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            String key = "client-" + i;
            if (splitter.decide("r", config, name -> name.equals("X-API-Key") ? key : null, n -> null, "ip").useCanary()) {
                canaryHits++;
            }
        }
        double ratio = (double) canaryHits / total;
        assertThat(ratio).isBetween(0.18, 0.22);
    }

    @Test
    void weightBoundariesSendNoneOrAll() {
        for (int i = 0; i < 300; i++) {
            String key = "k" + i;
            assertThat(splitter.decide("r", canary(0, "api_key"), n -> key, n -> null, "ip").useCanary()).isFalse();
            assertThat(splitter.decide("r", canary(100, "api_key"), n -> key, n -> null, "ip").useCanary()).isTrue();
        }
    }

    @Test
    void headerOverrideBeatsWeight() {
        assertThat(splitter.decide("r", canary(0, "api_key"), n -> n.equals("X-Canary") ? "always" : null, n -> null, "ip")
                .useCanary()).isTrue();
        assertThat(splitter.decide("r", canary(100, "api_key"), n -> n.equals("X-Canary") ? " NEVER " : null, n -> null, "ip")
                .useCanary()).isFalse();
        assertThat(splitter.decide("r", canary(100, "api_key"), n -> n.equals("X-Canary") ? "maybe" : "k", n -> null, "ip")
                .useCanary()).isTrue();
    }

    @Test
    void noCanaryConfigMeansStable() {
        assertThat(splitter.decide("r", null, n -> null, n -> null, "ip").useCanary()).isFalse();
    }

    @Test
    void stickyKeysSupportCookieHeaderAndIpFallback() {
        CanaryConfig cookie = canary(50, "cookie:session");
        boolean viaCookie = splitter.decide("r", cookie, n -> null, n -> n.equals("session") ? "abc" : null, "ip").useCanary();
        assertThat(splitter.decide("r", cookie, n -> null, n -> n.equals("session") ? "abc" : null, "other-ip").useCanary())
                .isEqualTo(viaCookie);
        CanaryConfig header = canary(50, "header:X-User");
        boolean viaHeader = splitter.decide("r", header, n -> n.equals("X-User") ? "u1" : null, n -> null, "ip").useCanary();
        assertThat(splitter.decide("r", header, n -> n.equals("X-User") ? "u1" : null, n -> null, "zzz").useCanary())
                .isEqualTo(viaHeader);
        CanaryConfig ip = canary(50, "client_ip");
        assertThat(splitter.decide("r", ip, n -> null, n -> null, "10.0.0.1").useCanary())
                .isEqualTo(splitter.decide("r", ip, n -> "ignored", n -> null, "10.0.0.1").useCanary());
        assertThat(splitter.decide("r", header, n -> null, n -> null, "10.0.0.1").useCanary())
                .isEqualTo(splitter.decide("r", ip, n -> null, n -> null, "10.0.0.1").useCanary());
    }

    @Test
    void bucketsAreSaltedByRouteName() {
        int differing = 0;
        for (int i = 0; i < 200; i++) {
            if (splitter.bucketFor("route-a", "k" + i) != splitter.bucketFor("route-b", "k" + i)) {
                differing++;
            }
        }
        assertThat(differing).isGreaterThan(150);
    }

    private static RoutingSnapshot snapshotWith(CanaryConfig canary) {
        RouteConfig config = Configs.route("shop", "/shop", "stable", false, 0, RetryConfig.defaults(), null, canary);
        CompiledRoute route = new CompiledRoute(config, null, null, null, null, new RetryPolicy(RetryConfig.defaults()),
                new RetryBudget(RetryConfig.defaults(), new MutableClock()));
        Map<String, CompiledRoute> routes = new HashMap<>();
        routes.put("shop", route);
        return new RoutingSnapshot(1, Instant.EPOCH, RouteMatcher.build(List.of(config)), routes, Map.of(),
                TenantDirectory.empty(), null, ConfigDocument.empty());
    }

    @Test
    void autoRollbackTriggersWhenErrorRateExceedsThresholdWithEnoughTraffic() {
        MutableClock clock = new MutableClock();
        CanaryMonitor monitor = new CanaryMonitor(clock);
        List<String> rollbacks = new ArrayList<>();
        CanaryWeightUpdater updater = (route, reason) -> rollbacks.add(route + ":" + reason);
        SnapshotHolder holder = new SnapshotHolder();
        CanaryConfig canary = new CanaryConfig("canary", 20, "api_key", new AutoRollbackConfig(40, 60, 10));
        holder.install(snapshotWith(canary));
        CanaryRollbackService service = new CanaryRollbackService(holder, monitor, updater,
                new SluiceMetrics(new SimpleMeterRegistry()));

        for (int i = 0; i < 9; i++) {
            monitor.record("shop", 60, true);
        }
        assertThat(service.evaluate(holder.current())).isEmpty();
        monitor.record("shop", 60, true);
        assertThat(service.evaluate(holder.current())).containsExactly("shop");
        assertThat(rollbacks).hasSize(1);
        assertThat(rollbacks.get(0)).contains("shop").contains("100%");
        assertThat(service.evaluate(holder.current())).isEmpty();
    }

    @Test
    void healthyCanaryIsLeftAlone() {
        MutableClock clock = new MutableClock();
        CanaryMonitor monitor = new CanaryMonitor(clock);
        List<String> rollbacks = new ArrayList<>();
        SnapshotHolder holder = new SnapshotHolder();
        holder.install(snapshotWith(new CanaryConfig("canary", 20, "api_key", new AutoRollbackConfig(50, 60, 10))));
        CanaryRollbackService service = new CanaryRollbackService(holder, monitor,
                (route, reason) -> rollbacks.add(route), new SluiceMetrics(new SimpleMeterRegistry()));
        for (int i = 0; i < 100; i++) {
            monitor.record("shop", 60, i % 10 == 0);
        }
        service.evaluateCurrentSnapshot();
        assertThat(rollbacks).isEmpty();
    }

    @Test
    void oldErrorsAgeOutOfTheWindowAndZeroWeightOrMissingPolicyAreSkipped() {
        MutableClock clock = new MutableClock();
        CanaryMonitor monitor = new CanaryMonitor(clock);
        List<String> rollbacks = new ArrayList<>();
        SnapshotHolder holder = new SnapshotHolder();
        CanaryRollbackService service = new CanaryRollbackService(holder, monitor,
                (route, reason) -> rollbacks.add(route), new SluiceMetrics(new SimpleMeterRegistry()));
        holder.install(snapshotWith(new CanaryConfig("canary", 20, "api_key", new AutoRollbackConfig(50, 10, 5))));
        for (int i = 0; i < 20; i++) {
            monitor.record("shop", 10, true);
        }
        clock.advance(Duration.ofSeconds(30));
        assertThat(service.evaluate(holder.current())).isEmpty();
        for (int i = 0; i < 20; i++) {
            monitor.record("shop", 10, true);
        }
        holder.install(snapshotWith(new CanaryConfig("canary", 0, "api_key", new AutoRollbackConfig(50, 10, 5))));
        assertThat(service.evaluate(holder.current())).isEmpty();
        holder.install(snapshotWith(new CanaryConfig("canary", 20, "api_key", null)));
        assertThat(service.evaluate(holder.current())).isEmpty();
        holder.install(snapshotWith(null));
        assertThat(service.evaluate(holder.current())).isEmpty();
        assertThat(rollbacks).isEmpty();
    }

    @Test
    void failedRollbackAttemptDoesNotCountAsRolledBack() {
        MutableClock clock = new MutableClock();
        CanaryMonitor monitor = new CanaryMonitor(clock);
        SnapshotHolder holder = new SnapshotHolder();
        holder.install(snapshotWith(new CanaryConfig("canary", 20, "api_key", new AutoRollbackConfig(50, 60, 5))));
        CanaryRollbackService service = new CanaryRollbackService(holder, monitor, (route, reason) -> false,
                new SluiceMetrics(new SimpleMeterRegistry()));
        for (int i = 0; i < 10; i++) {
            monitor.record("shop", 60, true);
        }
        assertThat(service.evaluate(holder.current())).isEmpty();
    }
}
