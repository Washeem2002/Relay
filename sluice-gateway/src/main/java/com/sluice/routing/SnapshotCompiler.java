package com.sluice.routing;

import com.sluice.auth.JwtVerifier;
import com.sluice.auth.TenantDirectory;
import com.sluice.balancing.BalancerFactory;
import com.sluice.balancing.CompiledTarget;
import com.sluice.balancing.PoolRuntime;
import com.sluice.balancing.PoolRuntimeRegistry;
import com.sluice.balancing.TargetState;
import com.sluice.balancing.TargetStateRegistry;
import com.sluice.cache.CachePolicy;
import com.sluice.config.ConfigValidationException;
import com.sluice.config.ConfigValidator;
import com.sluice.resilience.RetryBudget;
import com.sluice.resilience.RetryPolicy;
import com.sluice.schema.AuthMode;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class SnapshotCompiler {

    private final ConfigValidator validator;
    private final TargetStateRegistry targetStates;
    private final PoolRuntimeRegistry poolRuntimes;
    private final Clock clock;
    private final Function<String, String> environment = System::getenv;
    private final Map<String, RetryBudget> retryBudgets = new ConcurrentHashMap<>();

    public SnapshotCompiler(ConfigValidator validator, TargetStateRegistry targetStates,
                            PoolRuntimeRegistry poolRuntimes, Clock clock) {
        this.validator = validator;
        this.targetStates = targetStates;
        this.poolRuntimes = poolRuntimes;
        this.clock = clock;
    }

    public RoutingSnapshot compile(long version, ConfigDocument document) {
        validator.validateOrThrow(document);
        Map<String, CompiledPool> pools = compilePools(document);
        Map<String, CachePolicyConfig> cachePolicies = new HashMap<>();
        document.cachePolicies().forEach(policy -> cachePolicies.put(policy.name(), policy));
        Map<String, CompiledRoute> routes = new HashMap<>();
        for (RouteConfig route : document.routes()) {
            routes.put(route.name(), compileRoute(route, pools, cachePolicies));
        }
        return new RoutingSnapshot(version, Instant.now(clock), RouteMatcher.build(document.routes()),
                Map.copyOf(routes), Map.copyOf(pools), TenantDirectory.from(document), document.globalLimit(),
                document);
    }

    public void prune(RoutingSnapshot snapshot) {
        Set<String> targetKeys = new HashSet<>();
        for (CompiledPool pool : snapshot.pools().values()) {
            for (CompiledTarget target : pool.targets()) {
                targetKeys.add(TargetStateRegistry.keyOf(pool.name(), target.id(), target.config().url()));
            }
        }
        targetStates.retainOnly(targetKeys);
        poolRuntimes.retainOnly(snapshot.pools().keySet());
        Set<String> budgetKeys = new HashSet<>();
        snapshot.routes().values().forEach(route -> budgetKeys.add(route.name() + "|" + route.config().retry()));
        retryBudgets.keySet().retainAll(budgetKeys);
    }

    private Map<String, CompiledPool> compilePools(ConfigDocument document) {
        Map<String, CompiledPool> pools = new HashMap<>();
        for (PoolConfig pool : document.pools()) {
            List<CompiledTarget> targets = pool.targets().stream()
                    .map(target -> compileTarget(pool, target))
                    .toList();
            PoolRuntime runtime = poolRuntimes.obtain(pool);
            pools.put(pool.name(), new CompiledPool(pool, targets, BalancerFactory.create(pool, targets), runtime));
        }
        return pools;
    }

    private CompiledTarget compileTarget(PoolConfig pool, TargetConfig target) {
        TargetState state = targetStates.obtain(pool.name(), target.id(), target.url(), pool.circuitBreaker());
        return new CompiledTarget(target, URI.create(target.url()), state);
    }

    private CompiledRoute compileRoute(RouteConfig route, Map<String, CompiledPool> pools,
                                       Map<String, CachePolicyConfig> cachePolicies) {
        CompiledPool canary = route.canary() == null ? null : pools.get(route.canary().pool());
        CachePolicy cachePolicy = route.cachePolicy() == null ? null : new CachePolicy(cachePolicies.get(route.cachePolicy()));
        JwtVerifier verifier = null;
        if (route.auth().mode() == AuthMode.JWT) {
            try {
                verifier = JwtVerifier.create(route.auth().jwt(), clock, environment);
            } catch (Exception e) {
                throw new ConfigValidationException(List.of("route " + route.name() + ": " + e.getMessage()));
            }
        }
        return new CompiledRoute(route, pools.get(route.pool()), canary, cachePolicy, verifier,
                new RetryPolicy(route.retry()), retryBudget(route.name(), route.retry()));
    }

    private RetryBudget retryBudget(String routeName, RetryConfig config) {
        String key = routeName + "|" + config;
        return retryBudgets.computeIfAbsent(key, ignored -> new RetryBudget(config, clock));
    }
}
