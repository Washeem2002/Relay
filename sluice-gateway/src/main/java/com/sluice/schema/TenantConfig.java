package com.sluice.schema;

import java.util.List;

public record TenantConfig(String id, String name, String plan, boolean disabled, List<ApiKeyRecord> apiKeys) {

    public TenantConfig {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    public TenantConfig withApiKeys(List<ApiKeyRecord> keys) {
        return new TenantConfig(id, name, plan, disabled, keys);
    }
}
