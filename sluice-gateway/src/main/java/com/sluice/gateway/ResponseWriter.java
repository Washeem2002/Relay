package com.sluice.gateway;

import com.sluice.cache.CacheEntry;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ResponseWriter {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final HeaderPolicy headerPolicy;

    public ResponseWriter(HeaderPolicy headerPolicy) {
        this.headerPolicy = headerPolicy;
    }

    public void stream(UpstreamResult result, HttpServletResponse response, RequestContext context,
                       Map<String, String> extraHeaders, BodyCollector collector, boolean headRequest)
            throws IOException {
        response.setStatus(result.status());
        context.status(result.status());
        copyHeaders(result.headers(), response);
        response.setHeader("Via", headerPolicy.viaValue(joinedVia(result.headers())));
        response.setHeader("X-Request-ID", context.requestId());
        extraHeaders.forEach(response::setHeader);
        InputStream in = result.body();
        boolean bodyless = in == null || headRequest || result.status() == 204 || result.status() == 304;
        if (bodyless) {
            if (collector != null) {
                collector.markComplete();
            }
            return;
        }
        boolean flushEachChunk = result.contentLength() < 0;
        ServletOutputStream out = response.getOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            int read;
            try {
                read = in.read(buffer);
            } catch (IOException e) {
                throw new UpstreamStreamException(e);
            }
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (collector != null) {
                collector.accept(buffer, 0, read);
            }
            if (flushEachChunk) {
                out.flush();
            }
        }
        if (collector != null) {
            collector.markComplete();
        }
    }

    public void drain(UpstreamResult result, BodyCollector collector) throws IOException {
        InputStream in = result.body();
        if (in != null) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                collector.accept(buffer, 0, read);
            }
        }
        collector.markComplete();
    }

    public void writeCached(CacheEntry entry, HttpServletResponse response, RequestContext context, String cacheStatus,
                            boolean headRequest, long nowMillis, Map<String, String> extraHeaders) throws IOException {
        response.setStatus(entry.status());
        context.status(entry.status());
        copyHeaders(entry.headers(), response);
        response.setHeader("Via", headerPolicy.viaValue(joinedVia(entry.headers())));
        response.setHeader("X-Request-ID", context.requestId());
        response.setHeader("X-Cache", cacheStatus);
        response.setHeader("Age", String.valueOf(Math.max(0, (nowMillis - entry.storedAtMillis()) / 1000)));
        extraHeaders.forEach(response::setHeader);
        if (headRequest || entry.status() == 204 || entry.status() == 304) {
            return;
        }
        response.setContentLengthLong(entry.body().length);
        response.getOutputStream().write(entry.body());
    }

    public boolean shouldStoreHeader(String name, Set<String> connectionTokens, boolean headRequest) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (headerPolicy.isHopByHop(lower) || connectionTokens.contains(lower) || lower.equals("set-cookie")
                || lower.equals("age") || lower.equals("x-cache") || lower.equals("x-request-id")) {
            return false;
        }
        return headRequest || !lower.equals("content-length");
    }

    private void copyHeaders(Map<String, List<String>> headers, HttpServletResponse response) {
        Set<String> connectionTokens = headerPolicy.connectionTokens(headers.getOrDefault("Connection", List.of()));
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            String lower = header.getKey().toLowerCase(Locale.ROOT);
            if (headerPolicy.isHopByHop(lower) || connectionTokens.contains(lower) || lower.equals("via")
                    || lower.equals("x-request-id")) {
                continue;
            }
            for (String value : header.getValue()) {
                response.addHeader(header.getKey(), value);
            }
        }
    }

    private static String joinedVia(Map<String, List<String>> headers) {
        List<String> via = headers.get("Via");
        return via == null || via.isEmpty() ? null : String.join(", ", via);
    }
}
