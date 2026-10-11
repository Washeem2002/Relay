package com.sluice.gateway;

import com.sluice.canary.CanaryDecision;
import com.sluice.canary.CanaryMonitor;
import com.sluice.canary.CanarySplitter;
import com.sluice.gateway.middleware.AuthMiddleware;
import com.sluice.gateway.middleware.CacheMiddleware;
import com.sluice.gateway.middleware.Principal;
import com.sluice.gateway.middleware.RateLimitMiddleware;
import com.sluice.gateway.middleware.RequestLimits;
import com.sluice.routing.CompiledPool;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.PathNormalizer;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotHolder;
import com.sluice.routing.StickyKey;
import com.sluice.schema.CanaryConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class GatewayPipeline {

    private static final int DEFAULT_CANARY_WINDOW_SECONDS = 60;

    private final SnapshotHolder snapshots;
    private final RequestLimits limits;
    private final AuthMiddleware auth;
    private final RateLimitMiddleware rateLimit;
    private final CanarySplitter canarySplitter;
    private final CanaryMonitor canaryMonitor;
    private final CacheMiddleware cache;
    private final ProxyService proxy;
    private final ResponseWriter writer;
    private final HeaderPolicy headerPolicy;

    public GatewayPipeline(SnapshotHolder snapshots, RequestLimits limits, AuthMiddleware auth,
                           RateLimitMiddleware rateLimit, CanarySplitter canarySplitter, CanaryMonitor canaryMonitor,
                           CacheMiddleware cache, ProxyService proxy, ResponseWriter writer,
                           HeaderPolicy headerPolicy) {
        this.snapshots = snapshots;
        this.limits = limits;
        this.auth = auth;
        this.rateLimit = rateLimit;
        this.canarySplitter = canarySplitter;
        this.canaryMonitor = canaryMonitor;
        this.cache = cache;
        this.proxy = proxy;
        this.writer = writer;
        this.headerPolicy = headerPolicy;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response, RequestContext context)
            throws IOException {
        RoutingSnapshot snapshot = snapshots.current();
        limits.check(request);
        String rawPath = request.getRequestURI();
        if (PathNormalizer.containsTraversal(rawPath)) {
            throw new GatewayException(400, "bad_request", "path traversal sequences are not allowed");
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String host = PathNormalizer.hostWithoutPort(request.getHeader("Host"));
        CompiledRoute route = snapshot.match(host, rawPath, method).orElseThrow(() ->
                new GatewayException(404, "route_not_found", "no route matches " + method + " " + host + rawPath));
        context.route(route.name());
        String clientIp = headerPolicy.clientIp(request);
        Principal principal = auth.authenticate(route, snapshot, request, clientIp);
        context.tenant(principal.authenticated() ? principal.id() : "anonymous");
        rateLimit.apply(route, principal, snapshot, context, response);

        Function<String, String> header = request::getHeader;
        Function<String, String> cookie = name -> cookieValue(request, name);
        CanaryConfig canaryConfig = route.config().canary();
        CanaryDecision decision = canarySplitter.decide(route.name(), canaryConfig, header, cookie, clientIp);
        boolean useCanary = decision.useCanary() && route.canary() != null;
        CompiledPool pool = useCanary ? route.canary() : route.stable();
        if (canaryConfig != null) {
            context.decide(useCanary ? "canary" : "stable");
        }
        try {
            forward(route, pool, request, response, context, principal, clientIp, header, cookie, method);
        } catch (GatewayException e) {
            recordCanaryOutcome(route, useCanary, e.status());
            throw e;
        }
        recordCanaryOutcome(route, useCanary, context.status());
    }

    private void forward(CompiledRoute route, CompiledPool pool, HttpServletRequest request,
                         HttpServletResponse response, RequestContext context, Principal principal, String clientIp,
                         Function<String, String> header, Function<String, String> cookie, String method)
            throws IOException {
        String query = request.getQueryString();
        String pathAndQuery = route.rewritePath(request.getRequestURI()) + (query == null ? "" : "?" + query);
        long declaredLength = request.getContentLengthLong();
        boolean hasBody = declaredLength > 0 || request.getHeader("Transfer-Encoding") != null;
        BodyLimitInputStream body = hasBody
                ? new BodyLimitInputStream(request.getInputStream(), limits.maxBodyBytes()) : null;
        String hashKey = StickyKey.resolve(pool.config().hashKey(), header, cookie, clientIp);
        ForwardRequest forward = new ForwardRequest(method, pathAndQuery,
                headerPolicy.outboundHeaders(request, context.requestId(), clientIp,
                        principal.authenticated() ? principal.id() : null),
                body, hasBody ? (declaredLength > 0 ? declaredLength : -1) : 0, hashKey);
        boolean head = method.equals("HEAD");
        CacheMiddleware.Fetcher fetcher = () -> proxy.execute(route, pool, forward, context);
        if (cache.applies(route)) {
            ForwardRequest cacheable = forward.withHeaders(withoutAcceptEncoding(forward, route));
            CacheMiddleware.Fetcher cacheFetcher = () -> proxy.execute(route, pool, cacheable, context);
            CacheMiddleware.Fetcher refreshFetcher = () -> proxy.execute(route, pool, cacheable.withoutBody(),
                    new RequestContext(context.requestId() + ":refresh"));
            if (cache.serve(route, request, response, context, cacheFetcher, refreshFetcher)) {
                return;
            }
        }
        try (UpstreamResult result = fetcher.fetch()) {
            writer.stream(result, response, context, Map.of(), null, head);
        }
    }

    private java.util.List<HeaderPair> withoutAcceptEncoding(ForwardRequest forward, CompiledRoute route) {
        boolean variesOnEncoding = route.cachePolicy().config().varyHeaders().stream()
                .anyMatch(name -> name.equalsIgnoreCase("Accept-Encoding"));
        if (variesOnEncoding) {
            return forward.headers();
        }
        return forward.headers().stream()
                .filter(pair -> !pair.name().equalsIgnoreCase("Accept-Encoding"))
                .toList();
    }

    private void recordCanaryOutcome(CompiledRoute route, boolean useCanary, int status) {
        if (!useCanary) {
            return;
        }
        CanaryConfig config = route.config().canary();
        int window = config.autoRollback() == null ? DEFAULT_CANARY_WINDOW_SECONDS
                : config.autoRollback().windowSeconds();
        canaryMonitor.record(route.name(), window, status >= 500);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
