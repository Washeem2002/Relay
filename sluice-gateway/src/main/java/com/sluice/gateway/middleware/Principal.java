package com.sluice.gateway.middleware;

import com.sluice.auth.TenantInfo;

public record Principal(String id, TenantInfo tenant, boolean authenticated) {

    public static Principal anonymous(String clientIp) {
        return new Principal("ip:" + clientIp, null, false);
    }
}
