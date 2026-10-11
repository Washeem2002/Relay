package com.sluice.gateway;

import com.sluice.balancing.CompiledTarget;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;

public final class UpstreamResult implements AutoCloseable {

    private final ClassicHttpResponse response;
    private final CompiledTarget target;
    private final Runnable onClose;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final InputStream body;
    private final long contentLength;

    public UpstreamResult(ClassicHttpResponse response, CompiledTarget target, Runnable onClose) throws IOException {
        this.response = response;
        this.target = target;
        this.onClose = onClose;
        for (Header header : response.getHeaders()) {
            headers.computeIfAbsent(header.getName(), key -> new ArrayList<>()).add(header.getValue());
        }
        HttpEntity entity = response.getEntity();
        this.body = entity == null ? null : entity.getContent();
        this.contentLength = entity == null ? -1 : entity.getContentLength();
    }

    public int status() {
        return response.getCode();
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public InputStream body() {
        return body;
    }

    public long contentLength() {
        return contentLength;
    }

    public CompiledTarget target() {
        return target;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                response.close();
            } catch (IOException e) {
                target.state().circuit().releasePermit();
            } finally {
                target.state().connectionClosed();
                onClose.run();
            }
        }
    }
}
