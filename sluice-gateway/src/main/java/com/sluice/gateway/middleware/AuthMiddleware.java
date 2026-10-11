package com.sluice.gateway.middleware;

import com.sluice.auth.ApiKeyService;
import com.sluice.auth.JwtVerifier;
import com.sluice.auth.TenantInfo;
import com.sluice.gateway.GatewayException;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.RoutingSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AuthMiddleware {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeys;

    public AuthMiddleware(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    public Principal authenticate(CompiledRoute route, RoutingSnapshot snapshot, HttpServletRequest request,
                                  String clientIp) {
        return switch (route.config().auth().mode()) {
            case API_KEY -> requireApiKey(snapshot, request);
            case JWT -> requireJwt(route, snapshot, request);
            case NONE -> optionalApiKey(snapshot, request, clientIp);
        };
    }

    private Principal requireApiKey(RoutingSnapshot snapshot, HttpServletRequest request) {
        String key = request.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            throw new GatewayException(401, "missing_api_key", "the X-API-Key header is required",
                    Map.of("WWW-Authenticate", "ApiKey"));
        }
        return lookup(snapshot, key);
    }

    private Principal optionalApiKey(RoutingSnapshot snapshot, HttpServletRequest request, String clientIp) {
        String key = request.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            return Principal.anonymous(clientIp);
        }
        return lookup(snapshot, key);
    }

    private Principal lookup(RoutingSnapshot snapshot, String key) {
        Optional<TenantInfo> tenant = snapshot.tenants().authenticate(apiKeys.hash(key));
        if (tenant.isEmpty()) {
            throw new GatewayException(401, "invalid_api_key", "the API key is not valid",
                    Map.of("WWW-Authenticate", "ApiKey"));
        }
        return new Principal(tenant.get().id(), tenant.get(), true);
    }

    private Principal requireJwt(CompiledRoute route, RoutingSnapshot snapshot, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new GatewayException(401, "missing_token", "a bearer token is required",
                    Map.of("WWW-Authenticate", "Bearer"));
        }
        JwtVerifier verifier = route.jwtVerifier();
        Optional<JwtVerifier.Claims> claims = verifier.verify(header.substring(BEARER_PREFIX.length()).trim());
        if (claims.isEmpty()) {
            throw new GatewayException(401, "invalid_token", "the bearer token is not valid",
                    Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        }
        String tenantId = claims.get().tenant();
        TenantInfo tenant = snapshot.tenants().tenant(tenantId).orElse(null);
        return new Principal(tenantId, tenant, true);
    }
}
