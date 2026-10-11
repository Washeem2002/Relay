package com.sluice.auth;

import com.sluice.schema.ApiKeyRecord;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.TenantConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class TenantDirectory {

    private static final TenantDirectory EMPTY = new TenantDirectory(Map.of(), Map.of());

    private final Map<String, TenantInfo> byKeyHash;
    private final Map<String, TenantInfo> byId;

    private TenantDirectory(Map<String, TenantInfo> byKeyHash, Map<String, TenantInfo> byId) {
        this.byKeyHash = byKeyHash;
        this.byId = byId;
    }

    public static TenantDirectory empty() {
        return EMPTY;
    }

    public static TenantDirectory from(ConfigDocument document) {
        Map<String, PlanConfig> plans = new HashMap<>();
        document.plans().forEach(plan -> plans.put(plan.name(), plan));
        Map<String, TenantInfo> byKeyHash = new HashMap<>();
        Map<String, TenantInfo> byId = new HashMap<>();
        for (TenantConfig tenant : document.tenants()) {
            if (tenant.disabled()) {
                continue;
            }
            PlanConfig plan = tenant.plan() == null ? null : plans.get(tenant.plan());
            TenantInfo info = new TenantInfo(tenant.id(), tenant.name(), tenant.plan(),
                    plan == null ? null : plan.rateLimit());
            byId.put(tenant.id(), info);
            for (ApiKeyRecord key : tenant.apiKeys()) {
                if (!key.revoked()) {
                    byKeyHash.put(key.hash(), info);
                }
            }
        }
        return new TenantDirectory(Map.copyOf(byKeyHash), Map.copyOf(byId));
    }

    public Optional<TenantInfo> authenticate(String keyHash) {
        return Optional.ofNullable(byKeyHash.get(keyHash));
    }

    public Optional<TenantInfo> tenant(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int tenantCount() {
        return byId.size();
    }
}
