package com.sluice.schema;

import java.util.List;
import java.util.Optional;

public record ConfigDocument(
        List<PoolConfig> pools,
        List<RouteConfig> routes,
        List<PlanConfig> plans,
        List<TenantConfig> tenants,
        List<CachePolicyConfig> cachePolicies,
        RateLimitPolicy globalLimit) {

    public ConfigDocument {
        pools = pools == null ? List.of() : List.copyOf(pools);
        routes = routes == null ? List.of() : List.copyOf(routes);
        plans = plans == null ? List.of() : List.copyOf(plans);
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
        cachePolicies = cachePolicies == null ? List.of() : List.copyOf(cachePolicies);
    }

    public static ConfigDocument empty() {
        return new ConfigDocument(null, null, null, null, null, null);
    }

    public Optional<PoolConfig> findPool(String name) {
        return pools.stream().filter(pool -> pool.name().equals(name)).findFirst();
    }

    public Optional<RouteConfig> findRoute(String name) {
        return routes.stream().filter(route -> route.name().equals(name)).findFirst();
    }

    public Optional<TenantConfig> findTenant(String id) {
        return tenants.stream().filter(tenant -> tenant.id().equals(id)).findFirst();
    }

    public ConfigDocument withPools(List<PoolConfig> value) {
        return new ConfigDocument(value, routes, plans, tenants, cachePolicies, globalLimit);
    }

    public ConfigDocument withRoutes(List<RouteConfig> value) {
        return new ConfigDocument(pools, value, plans, tenants, cachePolicies, globalLimit);
    }

    public ConfigDocument withPlans(List<PlanConfig> value) {
        return new ConfigDocument(pools, routes, value, tenants, cachePolicies, globalLimit);
    }

    public ConfigDocument withTenants(List<TenantConfig> value) {
        return new ConfigDocument(pools, routes, plans, value, cachePolicies, globalLimit);
    }

    public ConfigDocument withCachePolicies(List<CachePolicyConfig> value) {
        return new ConfigDocument(pools, routes, plans, tenants, value, globalLimit);
    }

    public ConfigDocument withGlobalLimit(RateLimitPolicy value) {
        return new ConfigDocument(pools, routes, plans, tenants, cachePolicies, value);
    }
}
