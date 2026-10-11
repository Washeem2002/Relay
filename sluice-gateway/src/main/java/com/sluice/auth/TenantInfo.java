package com.sluice.auth;

import com.sluice.schema.RateLimitPolicy;

public record TenantInfo(String id, String name, String planName, RateLimitPolicy planPolicy) {
}
