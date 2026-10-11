package com.sluice.admin;

import com.sluice.auth.ApiKeyService;
import com.sluice.canary.CanaryWeightUpdater;
import com.sluice.config.ConfigService;
import com.sluice.schema.ApiKeyRecord;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import com.sluice.schema.TenantConfig;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class ConfigAdminService implements CanaryWeightUpdater {

    public static final String CANARY_ACTOR = "sluice-canary-monitor";

    public record CreatedKey(String keyId, String apiKey, String prefix) {
    }

    private final ConfigService config;
    private final ApiKeyService apiKeys;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ConfigAdminService(ConfigService config, ApiKeyService apiKeys, Clock clock) {
        this.config = config;
        this.apiKeys = apiKeys;
        this.clock = clock;
    }

    public ConfigDocument document() {
        return config.latest().map(ConfigService.VersionedConfig::document).orElse(ConfigDocument.empty());
    }

    public RouteConfig route(String name) {
        return document().findRoute(name).orElseThrow(() -> new NotFoundException("route " + name + " not found"));
    }

    public PoolConfig pool(String name) {
        return document().findPool(name).orElseThrow(() -> new NotFoundException("pool " + name + " not found"));
    }

    public TenantConfig tenant(String id) {
        return document().findTenant(id).orElseThrow(() -> new NotFoundException("tenant " + id + " not found"));
    }

    public ConfigService.MutationResult replaceAll(String actor, ConfigDocument replacement) {
        return config.mutate(actor, "CONFIG_REPLACE", "config", "replaced the whole configuration",
                current -> replacement);
    }

    public ConfigService.MutationResult createRoute(String actor, RouteConfig route) {
        return config.mutate(actor, "ROUTE_CREATE", "route:" + route.name(), "created route " + route.name(),
                doc -> doc.withRoutes(add(doc.routes(), route, RouteConfig::name, "route")));
    }

    public ConfigService.MutationResult updateRoute(String actor, String name, RouteConfig route) {
        requireSameName(name, route.name());
        return config.mutate(actor, "ROUTE_UPDATE", "route:" + name, "updated route " + name,
                doc -> doc.withRoutes(replace(doc.routes(), route, RouteConfig::name, name, "route")));
    }

    public ConfigService.MutationResult deleteRoute(String actor, String name) {
        return config.mutate(actor, "ROUTE_DELETE", "route:" + name, "deleted route " + name,
                doc -> doc.withRoutes(remove(doc.routes(), RouteConfig::name, name, "route")));
    }

    public ConfigService.MutationResult createPool(String actor, PoolConfig pool) {
        return config.mutate(actor, "POOL_CREATE", "pool:" + pool.name(), "created pool " + pool.name(),
                doc -> doc.withPools(add(doc.pools(), pool, PoolConfig::name, "pool")));
    }

    public ConfigService.MutationResult updatePool(String actor, String name, PoolConfig pool) {
        requireSameName(name, pool.name());
        return config.mutate(actor, "POOL_UPDATE", "pool:" + name, "updated pool " + name,
                doc -> doc.withPools(replace(doc.pools(), pool, PoolConfig::name, name, "pool")));
    }

    public ConfigService.MutationResult deletePool(String actor, String name) {
        return config.mutate(actor, "POOL_DELETE", "pool:" + name, "deleted pool " + name,
                doc -> doc.withPools(remove(doc.pools(), PoolConfig::name, name, "pool")));
    }

    public ConfigService.MutationResult addTarget(String actor, String poolName, TargetConfig target) {
        return config.mutate(actor, "TARGET_ADD", "pool:" + poolName, "added target " + target.id() + " to " + poolName,
                doc -> updatePoolTargets(doc, poolName, targets -> add(targets, target, TargetConfig::id, "target")));
    }

    public ConfigService.MutationResult updateTarget(String actor, String poolName, String targetId,
                                                     TargetConfig target) {
        requireSameName(targetId, target.id());
        return config.mutate(actor, "TARGET_UPDATE", "pool:" + poolName,
                "updated target " + targetId + " of " + poolName,
                doc -> updatePoolTargets(doc, poolName,
                        targets -> replace(targets, target, TargetConfig::id, targetId, "target")));
    }

    public ConfigService.MutationResult removeTarget(String actor, String poolName, String targetId) {
        return config.mutate(actor, "TARGET_REMOVE", "pool:" + poolName,
                "removed target " + targetId + " from " + poolName,
                doc -> updatePoolTargets(doc, poolName,
                        targets -> remove(targets, TargetConfig::id, targetId, "target")));
    }

    public ConfigService.MutationResult createPlan(String actor, PlanConfig plan) {
        return config.mutate(actor, "PLAN_CREATE", "plan:" + plan.name(), "created plan " + plan.name(),
                doc -> doc.withPlans(add(doc.plans(), plan, PlanConfig::name, "plan")));
    }

    public ConfigService.MutationResult updatePlan(String actor, String name, PlanConfig plan) {
        requireSameName(name, plan.name());
        return config.mutate(actor, "PLAN_UPDATE", "plan:" + name, "updated plan " + name,
                doc -> doc.withPlans(replace(doc.plans(), plan, PlanConfig::name, name, "plan")));
    }

    public ConfigService.MutationResult deletePlan(String actor, String name) {
        return config.mutate(actor, "PLAN_DELETE", "plan:" + name, "deleted plan " + name,
                doc -> doc.withPlans(remove(doc.plans(), PlanConfig::name, name, "plan")));
    }

    public ConfigService.MutationResult createCachePolicy(String actor, CachePolicyConfig policy) {
        return config.mutate(actor, "CACHE_POLICY_CREATE", "cachePolicy:" + policy.name(),
                "created cache policy " + policy.name(),
                doc -> doc.withCachePolicies(add(doc.cachePolicies(), policy, CachePolicyConfig::name, "cache policy")));
    }

    public ConfigService.MutationResult updateCachePolicy(String actor, String name, CachePolicyConfig policy) {
        requireSameName(name, policy.name());
        return config.mutate(actor, "CACHE_POLICY_UPDATE", "cachePolicy:" + name, "updated cache policy " + name,
                doc -> doc.withCachePolicies(
                        replace(doc.cachePolicies(), policy, CachePolicyConfig::name, name, "cache policy")));
    }

    public ConfigService.MutationResult deleteCachePolicy(String actor, String name) {
        return config.mutate(actor, "CACHE_POLICY_DELETE", "cachePolicy:" + name, "deleted cache policy " + name,
                doc -> doc.withCachePolicies(remove(doc.cachePolicies(), CachePolicyConfig::name, name, "cache policy")));
    }

    public ConfigService.MutationResult createTenant(String actor, TenantConfig tenant) {
        TenantConfig withoutKeys = tenant.withApiKeys(List.of());
        return config.mutate(actor, "TENANT_CREATE", "tenant:" + tenant.id(), "created tenant " + tenant.id(),
                doc -> doc.withTenants(add(doc.tenants(), withoutKeys, TenantConfig::id, "tenant")));
    }

    public ConfigService.MutationResult updateTenant(String actor, String id, TenantConfig tenant) {
        requireSameName(id, tenant.id());
        return config.mutate(actor, "TENANT_UPDATE", "tenant:" + id, "updated tenant " + id, doc -> {
            TenantConfig existing = doc.findTenant(id).orElseThrow(() -> new NotFoundException("tenant " + id + " not found"));
            TenantConfig merged = tenant.withApiKeys(existing.apiKeys());
            return doc.withTenants(replace(doc.tenants(), merged, TenantConfig::id, id, "tenant"));
        });
    }

    public ConfigService.MutationResult deleteTenant(String actor, String id) {
        return config.mutate(actor, "TENANT_DELETE", "tenant:" + id, "deleted tenant " + id,
                doc -> doc.withTenants(remove(doc.tenants(), TenantConfig::id, id, "tenant")));
    }

    public CreatedKey createApiKey(String actor, String tenantId) {
        String plaintext = apiKeys.generateKey();
        ApiKeyRecord record = new ApiKeyRecord("key-" + randomSuffix(), apiKeys.hash(plaintext),
                apiKeys.displayPrefix(plaintext), Instant.now(clock), false);
        config.mutate(actor, "API_KEY_CREATE", "tenant:" + tenantId,
                "issued api key " + record.id() + " for tenant " + tenantId, doc -> {
                    TenantConfig tenant = doc.findTenant(tenantId)
                            .orElseThrow(() -> new NotFoundException("tenant " + tenantId + " not found"));
                    List<ApiKeyRecord> keys = new ArrayList<>(tenant.apiKeys());
                    keys.add(record);
                    return doc.withTenants(replace(doc.tenants(), tenant.withApiKeys(keys), TenantConfig::id, tenantId,
                            "tenant"));
                });
        return new CreatedKey(record.id(), plaintext, record.prefix());
    }

    public ConfigService.MutationResult revokeApiKey(String actor, String tenantId, String keyId) {
        return config.mutate(actor, "API_KEY_REVOKE", "tenant:" + tenantId,
                "revoked api key " + keyId + " of tenant " + tenantId, doc -> {
                    TenantConfig tenant = doc.findTenant(tenantId)
                            .orElseThrow(() -> new NotFoundException("tenant " + tenantId + " not found"));
                    if (tenant.apiKeys().stream().noneMatch(key -> key.id().equals(keyId))) {
                        throw new NotFoundException("api key " + keyId + " not found");
                    }
                    List<ApiKeyRecord> keys = tenant.apiKeys().stream()
                            .map(key -> key.id().equals(keyId)
                                    ? new ApiKeyRecord(key.id(), key.hash(), key.prefix(), key.createdAt(), true) : key)
                            .toList();
                    return doc.withTenants(replace(doc.tenants(), tenant.withApiKeys(keys), TenantConfig::id, tenantId,
                            "tenant"));
                });
    }

    public ConfigService.MutationResult setCanary(String actor, String routeName, CanaryConfig canary) {
        return config.mutate(actor, "CANARY_SET", "route:" + routeName,
                "set canary on route " + routeName + " to weight " + canary.weight(),
                doc -> updateRoute(doc, routeName, route -> route.withCanary(canary)));
    }

    public ConfigService.MutationResult clearCanary(String actor, String routeName) {
        return config.mutate(actor, "CANARY_CLEAR", "route:" + routeName, "removed canary from route " + routeName,
                doc -> updateRoute(doc, routeName, route -> route.withCanary(null)));
    }

    public ConfigService.MutationResult setCanaryWeight(String actor, String routeName, int weight) {
        return config.mutate(actor, "CANARY_WEIGHT", "route:" + routeName,
                "set canary weight of route " + routeName + " to " + weight, doc -> updateRoute(doc, routeName, route -> {
                    if (route.canary() == null) {
                        throw new NotFoundException("route " + routeName + " has no canary");
                    }
                    return route.withCanary(route.canary().withWeight(weight));
                }));
    }

    @Override
    public boolean rollbackCanary(String routeName, String reason) {
        ConfigService.MutationResult result = config.mutate(CANARY_ACTOR, "CANARY_ROLLBACK", "route:" + routeName,
                reason, doc -> {
                    RouteConfig route = doc.findRoute(routeName).orElse(null);
                    if (route == null || route.canary() == null || route.canary().weight() == 0) {
                        return doc;
                    }
                    return updateRoute(doc, routeName, current -> current.withCanary(current.canary().withWeight(0)));
                });
        return result.changed();
    }

    public ConfigService.MutationResult setGlobalLimit(String actor, RateLimitPolicy policy) {
        return config.mutate(actor, policy == null ? "GLOBAL_LIMIT_CLEAR" : "GLOBAL_LIMIT_SET", "globalLimit",
                policy == null ? "removed the global rate limit" : "set the global rate limit",
                doc -> doc.withGlobalLimit(policy));
    }

    private ConfigDocument updateRoute(ConfigDocument doc, String routeName,
                                       java.util.function.UnaryOperator<RouteConfig> change) {
        RouteConfig existing = doc.findRoute(routeName)
                .orElseThrow(() -> new NotFoundException("route " + routeName + " not found"));
        return doc.withRoutes(replace(doc.routes(), change.apply(existing), RouteConfig::name, routeName, "route"));
    }

    private ConfigDocument updatePoolTargets(ConfigDocument doc, String poolName,
                                             java.util.function.UnaryOperator<List<TargetConfig>> change) {
        PoolConfig pool = doc.findPool(poolName).orElseThrow(() -> new NotFoundException("pool " + poolName + " not found"));
        return doc.withPools(replace(doc.pools(), pool.withTargets(change.apply(pool.targets())), PoolConfig::name,
                poolName, "pool"));
    }

    private static <T> List<T> add(List<T> items, T item, Function<T, String> key, String label) {
        String name = key.apply(item);
        if (items.stream().anyMatch(existing -> key.apply(existing).equals(name))) {
            throw new ConflictException(label + " " + name + " already exists");
        }
        List<T> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static <T> List<T> replace(List<T> items, T item, Function<T, String> key, String name, String label) {
        if (items.stream().noneMatch(existing -> key.apply(existing).equals(name))) {
            throw new NotFoundException(label + " " + name + " not found");
        }
        return items.stream().map(existing -> key.apply(existing).equals(name) ? item : existing).toList();
    }

    private static <T> List<T> remove(List<T> items, Function<T, String> key, String name, String label) {
        if (items.stream().noneMatch(existing -> key.apply(existing).equals(name))) {
            throw new NotFoundException(label + " " + name + " not found");
        }
        return items.stream().filter(existing -> !key.apply(existing).equals(name)).toList();
    }

    private static void requireSameName(String pathName, String bodyName) {
        if (!pathName.equals(bodyName)) {
            throw new IllegalArgumentException("the name in the path (" + pathName + ") must match the body ("
                    + bodyName + ")");
        }
    }

    private String randomSuffix() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
