package com.sluice.gateway;

import com.sluice.config.SluiceProperties;
import com.sluice.observability.SluiceMetrics;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger("sluice.access");
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final GatewayPipeline pipeline;
    private final ErrorResponseWriter errors;
    private final SluiceMetrics metrics;
    private final int adminPort;

    public GatewayFilter(GatewayPipeline pipeline, ErrorResponseWriter errors, SluiceMetrics metrics,
                         SluiceProperties properties) {
        this.pipeline = pipeline;
        this.errors = errors;
        this.metrics = metrics;
        this.adminPort = properties.admin().port();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (request.getLocalPort() == adminPort) {
            chain.doFilter(request, response);
            return;
        }
        RequestContext context = new RequestContext(requestId(request));
        MDC.put("requestId", context.requestId());
        response.setHeader("X-Request-ID", context.requestId());
        try {
            pipeline.handle(request, response, context);
        } catch (GatewayException e) {
            context.decide(e.code());
            context.status(e.status());
            errors.write(response, e, context);
        } catch (IOException e) {
            context.decide("io-error");
            handleIoFailure(response, context, e);
        } catch (RuntimeException e) {
            LOG.error("unhandled gateway error", e);
            GatewayException internal = new GatewayException(500, "internal_error", "the gateway failed to process the request");
            context.decide("internal-error");
            context.status(500);
            errors.write(response, internal, context);
        } finally {
            complete(request, context);
            MDC.remove("requestId");
        }
    }

    private void handleIoFailure(HttpServletResponse response, RequestContext context, IOException failure)
            throws IOException {
        if (failure instanceof UpstreamStreamException) {
            LOG.warn("upstream failed mid response for request {}: {}", context.requestId(), failure.getCause().toString());
            return;
        }
        if (!response.isCommitted()) {
            context.status(502);
            errors.write(response, new GatewayException(502, "gateway_io_error", "an i/o error occurred while proxying"),
                    context);
            return;
        }
        LOG.debug("client connection failed for request {}: {}", context.requestId(), failure.toString());
    }

    private void complete(HttpServletRequest request, RequestContext context) {
        int status = context.status() == 0 ? 200 : context.status();
        metrics.requestCompleted(context.route(), status, context.elapsedNanos());
        LOG.info("request completed",
                kv("method", request.getMethod()),
                kv("path", request.getRequestURI()),
                kv("route", context.route()),
                kv("tenant", context.tenant()),
                kv("pool", context.pool()),
                kv("target", context.target()),
                kv("status", status),
                kv("cache", context.cacheStatus() == null ? "-" : context.cacheStatus()),
                kv("decision", context.decisions()),
                kv("durationMs", context.elapsedNanos() / 1_000_000.0));
    }

    private static String requestId(HttpServletRequest request) {
        String inbound = request.getHeader("X-Request-ID");
        if (inbound != null && SAFE_REQUEST_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
