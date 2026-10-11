package com.sluice.gateway;

import java.util.List;

public record ForwardRequest(
        String method,
        String pathAndQuery,
        List<HeaderPair> headers,
        BodyLimitInputStream body,
        long contentLength,
        String hashKey) {

    public boolean bodyConsumed() {
        return body != null && body.bytesRead() > 0;
    }

    public ForwardRequest withoutBody() {
        return new ForwardRequest(method, pathAndQuery, headers, null, 0, hashKey);
    }

    public ForwardRequest withHeaders(List<HeaderPair> newHeaders) {
        return new ForwardRequest(method, pathAndQuery, newHeaders, body, contentLength, hashKey);
    }
}
