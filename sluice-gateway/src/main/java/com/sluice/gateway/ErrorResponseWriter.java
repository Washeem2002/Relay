package com.sluice.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ErrorResponseWriter {

    private final ObjectMapper mapper;

    public ErrorResponseWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, GatewayException error, RequestContext context) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(error.status());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Request-ID", context.requestId());
        error.headers().forEach(response::setHeader);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", error.code());
        inner.put("message", error.getMessage());
        inner.put("status", error.status());
        inner.put("requestId", context.requestId());
        byte[] body = mapper.writeValueAsBytes(Map.of("error", inner));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
