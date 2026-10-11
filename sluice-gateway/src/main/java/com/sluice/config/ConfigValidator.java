package com.sluice.config;

import com.sluice.infra.SsrfGuard;
import com.sluice.routing.PathNormalizer;
import com.sluice.schema.AuthMode;
import com.sluice.schema.CachePolicyConfig;
import com.sluice.schema.CanaryConfig;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.JwtAlgorithm;
import com.sluice.schema.JwtConfig;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.RouteConfig;
import com.sluice.schema.TargetConfig;
import com.sluice.schema.TenantConfig;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ConfigValidator {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final long MAX_CACHE_BODY_BYTES = 64L * 1024 * 1024;

    private final SluiceProperties properties;

    public ConfigValidator(SluiceProperties properties) {
        this.properties = properties;
    }

    public void validateOrThrow(ConfigDocument document) {
        List<String> errors = validate(document);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
    }

    public List<String> validate(ConfigDocument document) {
        List<String> errors = new ArrayList<>();
        Set<String> poolNames = validatePools(document.pools(), errors);
        Set<String> cacheNames = validateCachePolicies(document.cachePolicies(), errors);
        Set<String> planNames = validatePlans(document.plans(), errors);
        validateTenants(document.tenants(), planNames, errors);
        validateRoutes(document.routes(), poolNames, cacheNames, errors);
        if (document.globalLimit() != null) {
            validateRateLimit("globalLimit", document.globalLimit(), errors);
        }
        return errors;
    }

    private Set<String> validatePools(List<PoolConfig> pools, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (PoolConfig pool : pools) {
            String where = "pool " + pool.name();
            if (!validName(pool.name())) {
                errors.add("pool name is invalid: " + pool.name());
                continue;
            }
            if (!names.add(pool.name())) {
                errors.add(where + ": duplicate pool name");
            }
            if (pool.targets().isEmpty()) {
                errors.add(where + ": at least one target is required");
            }
            Set<String> targetIds = new HashSet<>();
            for (TargetConfig target : pool.targets()) {
                validateTarget(where, pool, target, targetIds, errors);
            }
            requireRange(errors, where + ": maxConnections", pool.maxConnections(), 1, 10_000);
            requireRange(errors, where + ": maxConcurrency", pool.maxConcurrency(), 1, 100_000);
            requireRange(errors, where + ": queueTimeoutMs", pool.queueTimeoutMs(), 1, 60_000);
            requireRange(errors, where + ": connectTimeoutMs", pool.connectTimeoutMs(), 1, 60_000);
            requireRange(errors, where + ": healthIntervalMs", pool.healthIntervalMs(), 100, 3_600_000);
            requireRange(errors, where + ": healthTimeoutMs", pool.healthTimeoutMs(), 1, 60_000);
            requireRange(errors, where + ": ejectAfterFailures", pool.ejectAfterFailures(), 1, 1_000);
            requireRange(errors, where + ": ejectDurationMs", pool.ejectDurationMs(), 1, 3_600_000);
            if (pool.healthPath() != null && !pool.healthPath().startsWith("/")) {
                errors.add(where + ": healthPath must start with /");
            }
            if (!validStickyKey(pool.hashKey(), true)) {
                errors.add(where + ": hashKey must be api_key, client_ip, header:Name or cookie:name");
            }
            validateCircuit(where, pool.circuitBreaker(), errors);
        }
        return names;
    }

    private void validateTarget(String where, PoolConfig pool, TargetConfig target, Set<String> ids,
                                List<String> errors) {
        if (target.id() == null || !validName(target.id())) {
            errors.add(where + ": target id is invalid: " + target.id());
            return;
        }
        if (!ids.add(target.id())) {
            errors.add(where + ": duplicate target id " + target.id());
        }
        requireRange(errors, where + " target " + target.id() + ": weight", target.weight(), 1, 1_000);
        URI uri;
        try {
            uri = URI.create(target.url() == null ? "" : target.url());
        } catch (IllegalArgumentException e) {
            errors.add(where + " target " + target.id() + ": url is not a valid uri");
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            errors.add(where + " target " + target.id() + ": url scheme must be http or https");
            return;
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            errors.add(where + " target " + target.id() + ": url must have a host and no credentials, query or fragment");
            return;
        }
        if (!pool.allowPrivateTargets() && resolvesToPrivateAddress(uri.getHost())) {
            errors.add(where + " target " + target.id() + ": host " + uri.getHost()
                    + " is a private or reserved address and allowPrivateTargets is false");
        }
    }

    private boolean resolvesToPrivateAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (SsrfGuard.isForbidden(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void validateCircuit(String where, CircuitBreakerConfig circuit, List<String> errors) {
        requireRange(errors, where + ": circuitBreaker.failureRatePercent", circuit.failureRatePercent(), 1, 100);
        requireRange(errors, where + ": circuitBreaker.windowSeconds", circuit.windowSeconds(), 1, 3_600);
        requireRange(errors, where + ": circuitBreaker.minRequests", circuit.minRequests(), 1, 100_000);
        requireRange(errors, where + ": circuitBreaker.cooldownMs", circuit.cooldownMs(), 1, 3_600_000);
        requireRange(errors, where + ": circuitBreaker.halfOpenMaxProbes", circuit.halfOpenMaxProbes(), 1, 1_000);
    }

    private Set<String> validateCachePolicies(List<CachePolicyConfig> policies, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (CachePolicyConfig policy : policies) {
            String where = "cachePolicy " + policy.name();
            if (!validName(policy.name())) {
                errors.add("cache policy name is invalid: " + policy.name());
                continue;
            }
            if (!names.add(policy.name())) {
                errors.add(where + ": duplicate name");
            }
            requireRange(errors, where + ": ttlSeconds", policy.ttlSeconds(), 1, 31_536_000);
            requireRange(errors, where + ": staleWhileRevalidateSeconds", policy.staleWhileRevalidateSeconds(), 0,
                    31_536_000);
            if (policy.maxBodyBytes() < 1 || policy.maxBodyBytes() > MAX_CACHE_BODY_BYTES) {
                errors.add(where + ": maxBodyBytes must be between 1 and " + MAX_CACHE_BODY_BYTES);
            }
            for (String header : policy.varyHeaders()) {
                if (!HEADER_NAME.matcher(header).matches()) {
                    errors.add(where + ": invalid vary header " + header);
                }
            }
        }
        return names;
    }

    private Set<String> validatePlans(List<PlanConfig> plans, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (PlanConfig plan : plans) {
            if (!validName(plan.name())) {
                errors.add("plan name is invalid: " + plan.name());
                continue;
            }
            if (!names.add(plan.name())) {
                errors.add("plan " + plan.name() + ": duplicate name");
            }
            if (plan.rateLimit() == null) {
                errors.add("plan " + plan.name() + ": rateLimit is required");
            } else {
                validateRateLimit("plan " + plan.name(), plan.rateLimit(), errors);
            }
        }
        return names;
    }

    private void validateTenants(List<TenantConfig> tenants, Set<String> planNames, List<String> errors) {
        Set<String> ids = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (TenantConfig tenant : tenants) {
            if (!validName(tenant.id())) {
                errors.add("tenant id is invalid: " + tenant.id());
                continue;
            }
            if (!ids.add(tenant.id())) {
                errors.add("tenant " + tenant.id() + ": duplicate id");
            }
            if (tenant.plan() != null && !planNames.contains(tenant.plan())) {
                errors.add("tenant " + tenant.id() + ": unknown plan " + tenant.plan());
            }
            Set<String> keyIds = new HashSet<>();
            tenant.apiKeys().forEach(key -> {
                if (key.id() == null || key.hash() == null || !keyIds.add(key.id())) {
                    errors.add("tenant " + tenant.id() + ": api key records need unique ids and hashes");
                } else if (!hashes.add(key.hash())) {
                    errors.add("tenant " + tenant.id() + ": api key hash is already used by another key");
                }
            });
        }
    }

    private void validateRoutes(List<RouteConfig> routes, Set<String> poolNames, Set<String> cacheNames,
                                List<String> errors) {
        Set<String> names = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        for (RouteConfig route : routes) {
            String where = "route " + route.name();
            if (!validName(route.name())) {
                errors.add("route name is invalid: " + route.name());
                continue;
            }
            if (!names.add(route.name())) {
                errors.add(where + ": duplicate route name");
            }
            validateRouteMatch(where, route, signatures, errors);
            if (route.pool() == null || !poolNames.contains(route.pool())) {
                errors.add(where + ": unknown pool " + route.pool());
            }
            requireRange(errors, where + ": timeoutMs", route.timeoutMs(), 1, properties.security().maxUpstreamTimeoutMs());
            validateRetry(where, route.retry(), errors);
            validateAuth(where, route, errors);
            if (route.rateLimit() != null) {
                validateRateLimit(where + " rateLimit", route.rateLimit(), errors);
            }
            if (route.cachePolicy() != null && !cacheNames.contains(route.cachePolicy())) {
                errors.add(where + ": unknown cache policy " + route.cachePolicy());
            }
            if (route.canary() != null) {
                validateCanary(where, route, poolNames, errors);
            }
        }
    }

    private void validateRouteMatch(String where, RouteConfig route, Set<String> signatures, List<String> errors) {
        String prefix = route.pathPrefix();
        if (prefix == null || !prefix.startsWith("/")) {
            errors.add(where + ": pathPrefix must start with /");
            return;
        }
        if (PathNormalizer.containsTraversal(prefix) || prefix.contains("?") || prefix.contains("#")) {
            errors.add(where + ": pathPrefix must not contain traversal, query or fragment characters");
        }
        for (String method : route.methods()) {
            if (!HTTP_METHODS.contains(method)) {
                errors.add(where + ": unsupported method " + method);
            }
        }
        if (route.host() != null && !route.host().equals("*")
                && !route.host().matches("(\\*\\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) {
            errors.add(where + ": host is invalid: " + route.host());
        }
        if (route.prefixReplacement() != null) {
            if (!route.stripPrefix()) {
                errors.add(where + ": prefixReplacement requires stripPrefix");
            }
            if (!route.prefixReplacement().startsWith("/")) {
                errors.add(where + ": prefixReplacement must start with /");
            }
        }
        String signature = (route.host() == null ? "*" : route.host()) + " "
                + String.join("/", PathNormalizer.segments(prefix)) + " " + route.methods();
        if (!signatures.add(signature)) {
            errors.add(where + ": another route has the same host, path prefix and methods");
        }
    }

    private void validateRetry(String where, RetryConfig retry, List<String> errors) {
        requireRange(errors, where + ": retry.maxAttempts", retry.maxAttempts(), 1, 10);
        requireRange(errors, where + ": retry.budgetPercent", retry.budgetPercent(), 1, 100);
        requireRange(errors, where + ": retry.budgetMinRetries", retry.budgetMinRetries(), 0, 100_000);
        requireRange(errors, where + ": retry.budgetWindowSeconds", retry.budgetWindowSeconds(), 1, 3_600);
    }

    private void validateAuth(String where, RouteConfig route, List<String> errors) {
        if (route.auth().mode() != AuthMode.JWT) {
            return;
        }
        JwtConfig jwt = route.auth().jwt();
        if (jwt == null) {
            errors.add(where + ": auth.jwt is required when mode is JWT");
            return;
        }
        if (jwt.algorithm() == JwtAlgorithm.HS256 && (jwt.secretEnv() == null || jwt.secretEnv().isBlank())) {
            errors.add(where + ": auth.jwt.secretEnv is required for HS256");
        }
        if (jwt.algorithm() == JwtAlgorithm.RS256 && (jwt.jwksPath() == null || jwt.jwksPath().isBlank())) {
            errors.add(where + ": auth.jwt.jwksPath is required for RS256");
        }
    }

    private void validateCanary(String where, RouteConfig route, Set<String> poolNames, List<String> errors) {
        CanaryConfig canary = route.canary();
        if (canary.pool() == null || !poolNames.contains(canary.pool())) {
            errors.add(where + ": unknown canary pool " + canary.pool());
        } else if (canary.pool().equals(route.pool())) {
            errors.add(where + ": canary pool must differ from the stable pool");
        }
        requireRange(errors, where + ": canary.weight", canary.weight(), 0, 100);
        if (!validStickyKey(canary.stickyKey(), true)) {
            errors.add(where + ": canary.stickyKey must be api_key, client_ip, header:Name or cookie:name");
        }
        if (canary.autoRollback() != null) {
            requireRange(errors, where + ": canary.autoRollback.errorRatePercent",
                    canary.autoRollback().errorRatePercent(), 1, 100);
            requireRange(errors, where + ": canary.autoRollback.windowSeconds",
                    canary.autoRollback().windowSeconds(), 1, 3_600);
            requireRange(errors, where + ": canary.autoRollback.minRequests",
                    canary.autoRollback().minRequests(), 1, 1_000_000);
        }
    }

    private void validateRateLimit(String where, RateLimitPolicy policy, List<String> errors) {
        if (policy.limit() < 1) {
            errors.add(where + ": limit must be at least 1");
        }
        requireRange(errors, where + ": windowSeconds", policy.windowSeconds(), 1, 86_400);
        if (policy.burst() < 1) {
            errors.add(where + ": burst must be at least 1");
        }
    }

    private static boolean validStickyKey(String value, boolean allowCookieAndApiKey) {
        if (value.equals("client_ip")) {
            return true;
        }
        if (allowCookieAndApiKey && value.equals("api_key")) {
            return true;
        }
        if (value.startsWith("header:")) {
            return HEADER_NAME.matcher(value.substring(7)).matches();
        }
        return allowCookieAndApiKey && value.startsWith("cookie:") && HEADER_NAME.matcher(value.substring(7)).matches();
    }

    private static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static void requireRange(List<String> errors, String label, long value, long min, long max) {
        if (value < min || value > max) {
            errors.add(label + " must be between " + min + " and " + max + " but was " + value);
        }
    }
}
