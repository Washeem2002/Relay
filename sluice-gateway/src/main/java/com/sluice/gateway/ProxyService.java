package com.sluice.gateway;

import com.sluice.balancing.CompiledTarget;
import com.sluice.balancing.TargetState;
import com.sluice.observability.SluiceMetrics;
import com.sluice.resilience.Bulkhead;
import com.sluice.resilience.RetryPolicy;
import com.sluice.routing.CompiledPool;
import com.sluice.routing.CompiledRoute;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

@Service
public class ProxyService {

    private final SluiceMetrics metrics;

    public ProxyService(SluiceMetrics metrics) {
        this.metrics = metrics;
    }

    public UpstreamResult execute(CompiledRoute route, CompiledPool pool, ForwardRequest request,
                                  RequestContext context) {
        Bulkhead bulkhead = pool.runtime().bulkhead();
        if (!bulkhead.tryAcquire()) {
            context.decide("bulkhead-rejected");
            throw new GatewayException(503, "pool_saturated", "upstream pool " + pool.name()
                    + " is at its concurrency limit", Map.of("Retry-After", "1"));
        }
        boolean handedOff = false;
        try {
            UpstreamResult result = executeWithRetries(route, pool, request, context, bulkhead::release);
            handedOff = true;
            return result;
        } finally {
            if (!handedOff) {
                bulkhead.release();
            }
        }
    }

    private UpstreamResult executeWithRetries(CompiledRoute route, CompiledPool pool, ForwardRequest request,
                                              RequestContext context, Runnable releaseBulkhead) {
        RetryPolicy policy = route.retryPolicy();
        route.retryBudget().recordRequest();
        Set<String> tried = new HashSet<>();
        int attempts = 0;
        while (true) {
            CompiledTarget target = selectTarget(pool, request.hashKey(), tried);
            attempts++;
            tried.add(target.id());
            context.target(target.id());
            context.pool(pool.name());
            long startNanos = System.nanoTime();
            try {
                ClassicHttpResponse response = send(pool, target, request, route.config().timeoutMs());
                metrics.upstreamCompleted(pool.name(), target.id(), System.nanoTime() - startNanos);
                int status = response.getCode();
                recordResponse(pool, target.state(), status);
                if (policy.isRetryableStatus(status)
                        && policy.shouldRetry(attempts, request.method(), request.bodyConsumed())
                        && route.retryBudget().tryAcquireRetry()) {
                    context.decide("retry");
                    discard(response, target);
                    continue;
                }
                return new UpstreamResult(response, target, releaseBulkhead);
            } catch (IOException e) {
                target.state().connectionClosed();
                GatewayException mapped = classify(e, pool, target.state());
                metrics.upstreamCompleted(pool.name(), target.id(), System.nanoTime() - startNanos);
                if (isUpstreamFault(mapped)
                        && policy.shouldRetry(attempts, request.method(), request.bodyConsumed())
                        && route.retryBudget().tryAcquireRetry()) {
                    context.decide("retry");
                    continue;
                }
                throw mapped;
            } catch (RuntimeException e) {
                target.state().connectionClosed();
                target.state().circuit().releasePermit();
                throw e;
            }
        }
    }

    private CompiledTarget selectTarget(CompiledPool pool, String hashKey, Set<String> tried) {
        List<CompiledTarget> candidates = new ArrayList<>(pool.selectableTargets());
        List<CompiledTarget> untried = candidates.stream().filter(target -> !tried.contains(target.id())).toList();
        List<CompiledTarget> pickFrom = new ArrayList<>(untried.isEmpty() ? candidates : untried);
        while (!pickFrom.isEmpty()) {
            Optional<CompiledTarget> chosen = pool.balancer().choose(pickFrom, hashKey);
            if (chosen.isEmpty()) {
                break;
            }
            CompiledTarget target = chosen.get();
            if (target.state().circuit().tryAcquire()) {
                target.state().connectionOpened();
                return target;
            }
            pickFrom.remove(target);
        }
        boolean anyHealthy = pool.targets().stream().anyMatch(target -> target.state().isHealthEligible());
        if (anyHealthy) {
            throw new GatewayException(503, "circuit_open", "all upstream targets of pool " + pool.name()
                    + " are failing fast because their circuits are open", Map.of("Retry-After", "1"));
        }
        throw new GatewayException(503, "no_healthy_target", "pool " + pool.name() + " has no healthy target",
                Map.of("Retry-After", "1"));
    }

    private ClassicHttpResponse send(CompiledPool pool, CompiledTarget target, ForwardRequest request, int timeoutMs)
            throws IOException {
        String base = target.config().url();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(base + request.pathAndQuery());
        } catch (IllegalArgumentException e) {
            throw new GatewayException(400, "bad_request", "request target is not a valid uri");
        }
        HttpUriRequestBase http = new HttpUriRequestBase(request.method(), uri);
        for (HeaderPair header : request.headers()) {
            http.addHeader(header.name(), header.value());
        }
        if (request.body() != null) {
            http.setEntity(new InputStreamEntity(request.body(), request.contentLength(), null));
        }
        http.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(pool.config().queueTimeoutMs(), 1_000)))
                .build());
        return pool.runtime().client().executeOpen(null, http, null);
    }

    private void recordResponse(CompiledPool pool, TargetState state, int status) {
        if (status >= 500) {
            state.circuit().recordFailure();
        } else {
            state.circuit().recordSuccess();
        }
        if (status == 502 || status == 503 || status == 504) {
            state.recordHealthFailure(pool.config().ejectAfterFailures(), pool.config().ejectDurationMs());
        } else {
            state.recordHealthSuccess();
        }
    }

    private GatewayException classify(IOException failure, CompiledPool pool, TargetState state) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                state.circuit().releasePermit();
                return new GatewayException(413, "payload_too_large", tooLarge.getMessage());
            }
            if (cause instanceof ClientReadException) {
                state.circuit().releasePermit();
                return new GatewayException(400, "client_read_error", "request body could not be read from the client");
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        if (failure instanceof ConnectionRequestTimeoutException) {
            state.circuit().releasePermit();
            return new GatewayException(503, "pool_exhausted", "no upstream connection became available in time",
                    Map.of("Retry-After", "1"));
        }
        state.circuit().recordFailure();
        state.recordHealthFailure(pool.config().ejectAfterFailures(), pool.config().ejectDurationMs());
        if (failure instanceof ConnectTimeoutException || failure instanceof SocketTimeoutException
                || failure instanceof InterruptedIOException) {
            return new GatewayException(504, "upstream_timeout", "upstream did not respond in time");
        }
        return new GatewayException(502, "upstream_unavailable", "upstream request failed: "
                + failure.getClass().getSimpleName());
    }

    private static boolean isUpstreamFault(GatewayException error) {
        return error.status() == 502 || error.status() == 504;
    }

    private static void discard(ClassicHttpResponse response, CompiledTarget target) {
        try {
            response.close();
        } catch (IOException ignored) {
            target.state().circuit().releasePermit();
        } finally {
            target.state().connectionClosed();
        }
    }
}
