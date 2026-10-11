package com.sluice.schema;

public record JwtConfig(
        JwtAlgorithm algorithm,
        String secretEnv,
        String jwksPath,
        String issuer,
        String audience,
        String tenantClaim) {

    public JwtConfig {
        if (algorithm == null) {
            algorithm = JwtAlgorithm.HS256;
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            tenantClaim = "tenant";
        }
    }
}
