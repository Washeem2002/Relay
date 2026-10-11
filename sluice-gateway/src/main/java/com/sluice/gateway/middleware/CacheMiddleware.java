package com.sluice.gateway.middleware;

import com.sluice.cache.CacheEntry;
import com.sluice.cache.CacheKeyBuilder;
import com.sluice.cache.CachePolicy;
import com.sluice.cache.CacheStore;
import com.sluice.cache.RequestCoalescer;
import com.sluice.config.SluiceProperties;
import com.sluice.gateway.BodyCollector;
import com.sluice.gateway.HeaderPolicy;
import com.sluice.gateway.RequestContext;
import com.sluice.gateway.ResponseWriter;
import com.sluice.gateway.UpstreamResult;
import com.sluice.observability.SluiceMetrics;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.PathNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CacheMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(CacheMiddleware.class);

    @FunctionalInterface
    public interface Fetcher {
        UpstreamResult fetch();
    }

    private final CacheStore store;
    private final RequestCoalescer coalescer;
    private final ResponseWriter writer;
    private final HeaderPolicy headerPolicy;
    private final SluiceProperties properties;
    private final Clock clock;
    private final SluiceMetrics metrics;

    public CacheMiddleware(CacheStore store, RequestCoalescer coalescer, ResponseWriter writer,
                           HeaderPolicy headerPolicy, SluiceProperties properties, Clock clock,
                           SluiceMetrics metrics) {
        this.store = store;
        this.coalescer = coalescer;
        this.writer = writer;
        this.headerPolicy = headerPolicy;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    public boolean applies(CompiledRoute route) {
        return route.cachePolicy() != null;
    }

    public boolean serve(CompiledRoute route, HttpServletRequest request, HttpServletResponse response,
                         RequestContext context, Fetcher fetcher, Fetcher refreshFetcher) throws IOException {
        CachePolicy policy = route.cachePolicy();
        if (!policy.requestEligible(request.getMethod(), request::getHeader)) {
            markBypass(route, response, context);
            return false;
        }
        boolean head = request.getMethod().equals("HEAD");
        String key = CacheKeyBuilder.build(request.getMethod(), PathNormalizer.hostWithoutPort(request.getHeader("Host")),
                request.getRequestURI(), request.getQueryString(), policy.config().varyHeaders(), request::getHeader);
        Optional<CacheEntry> found;
        try {
            found = store.get(key);
        } catch (RuntimeException e) {
            LOG.warn("cache store unavailable, bypassing cache: {}", e.toString());
            markBypass(route, response, context);
            return false;
        }
        long now = clock.millis();
        if (found.isPresent() && found.get().isFresh(now)) {
            record(route, context, "HIT");
            writer.writeCached(found.get(), response, context, "HIT", head, now, Map.of());
            return true;
        }
        if (found.isPresent() && found.get().isServableStale(now)) {
            record(route, context, "STALE");
            writer.writeCached(found.get(), response, context, "STALE", head, now, Map.of());
            refreshInBackground(route, key, policy, refreshFetcher, head);
            return true;
        }
        fetchAndStore(route, response, context, key, policy, fetcher, head);
        return true;
    }

    private void fetchAndStore(CompiledRoute route, HttpServletResponse response, RequestContext context, String key,
                               CachePolicy policy, Fetcher fetcher, boolean head) throws IOException {
        RequestCoalescer.Ticket ticket = coalescer.join(key);
        if (!ticket.leader()) {
            Optional<CacheEntry> shared = coalescer.await(ticket, properties.cache().coalesceWaitMs());
            if (shared.isPresent()) {
                context.decide("cache-coalesced");
                record(route, context, "HIT");
                writer.writeCached(shared.get(), response, context, "HIT", head, clock.millis(),
                        Map.of("X-Cache-Coalesced", "true"));
                return;
            }
            record(route, context, "MISS");
            try (UpstreamResult result = fetcher.fetch()) {
                writer.stream(result, response, context, Map.of("X-Cache", "MISS"), null, head);
            }
            return;
        }
        Optional<CacheEntry> produced = Optional.empty();
        try {
            record(route, context, "MISS");
            try (UpstreamResult result = fetcher.fetch()) {
                BodyCollector collector = isCollectable(policy, result) ? new BodyCollector(policy.config().maxBodyBytes())
                        : null;
                writer.stream(result, response, context, Map.of("X-Cache", "MISS"), collector, head);
                if (collector != null) {
                    produced = buildEntry(result, collector, policy, head);
                }
            }
        } finally {
            produced.ifPresent(entry -> persist(key, entry));
            coalescer.complete(key, ticket, produced);
        }
    }

    private void refreshInBackground(CompiledRoute route, String key, CachePolicy policy, Fetcher refreshFetcher,
                                     boolean head) {
        if (!coalescer.tryStartRefresh(key)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try (UpstreamResult result = refreshFetcher.fetch()) {
                if (isCollectable(policy, result)) {
                    BodyCollector collector = new BodyCollector(policy.config().maxBodyBytes());
                    if (!head) {
                        writer.drain(result, collector);
                    } else {
                        collector.markComplete();
                    }
                    buildEntry(result, collector, policy, head).ifPresent(entry -> persist(key, entry));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("background revalidation of {} failed: {}", route.name(), e.toString());
            } finally {
                coalescer.finishRefresh(key);
            }
        });
    }

    private boolean isCollectable(CachePolicy policy, UpstreamResult result) {
        return policy.storableFreshMillis(result.status(), result.headers()).isPresent()
                && (result.contentLength() < 0 || policy.bodyWithinLimit(result.contentLength()));
    }

    private Optional<CacheEntry> buildEntry(UpstreamResult result, BodyCollector collector, CachePolicy policy,
                                            boolean head) {
        Optional<Long> freshMillis = policy.storableFreshMillis(result.status(), result.headers());
        if (freshMillis.isEmpty() || !collector.isUsable()) {
            return Optional.empty();
        }
        Set<String> connectionTokens = headerPolicy.connectionTokens(
                result.headers().getOrDefault("Connection", List.of()));
        Map<String, List<String>> stored = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> header : result.headers().entrySet()) {
            if (writer.shouldStoreHeader(header.getKey(), connectionTokens, head)) {
                stored.put(header.getKey(), List.copyOf(header.getValue()));
            }
        }
        long now = clock.millis();
        long freshUntil = now + freshMillis.get();
        long staleUntil = freshUntil + policy.staleWindowMillis();
        return Optional.of(new CacheEntry(result.status(), stored, head ? new byte[0] : collector.bytes(), now,
                freshUntil, staleUntil));
    }

    private void persist(String key, CacheEntry entry) {
        try {
            store.put(key, entry, entry.staleUntilMillis() - entry.storedAtMillis());
        } catch (RuntimeException e) {
            LOG.warn("cache store write failed: {}", e.toString());
        }
    }

    private void markBypass(CompiledRoute route, HttpServletResponse response, RequestContext context) {
        response.setHeader("X-Cache", "BYPASS");
        record(route, context, "BYPASS");
    }

    private void record(CompiledRoute route, RequestContext context, String status) {
        context.cacheStatus(status);
        metrics.cacheResult(route.name(), status.toLowerCase());
    }
}
