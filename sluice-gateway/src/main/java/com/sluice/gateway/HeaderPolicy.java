package com.sluice.gateway;

import com.sluice.config.SluiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HeaderPolicy {

    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");
    private static final Set<String> REPLACED_ON_REQUEST = Set.of("host", "content-length", "expect", "x-api-key",
            "x-canary", "x-request-id", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-tenant-id",
            "via");

    private final String viaToken;
    private final boolean trustForwardedFor;

    public HeaderPolicy(SluiceProperties properties) {
        this.viaToken = properties.gateway().viaToken();
        this.trustForwardedFor = properties.security().trustForwardedFor();
    }

    public List<HeaderPair> outboundHeaders(HttpServletRequest request, String requestId, String clientIp,
                                            String tenantId) {
        Set<String> connectionTokens = connectionTokens(Collections.list(request.getHeaders("Connection")));
        List<HeaderPair> headers = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || REPLACED_ON_REQUEST.contains(lower) || connectionTokens.contains(lower)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(new HeaderPair(name, value));
            }
        }
        String existingForwardedFor = trustForwardedFor ? joined(request.getHeaders("X-Forwarded-For")) : null;
        headers.add(new HeaderPair("X-Forwarded-For",
                existingForwardedFor == null ? clientIp : existingForwardedFor + ", " + clientIp));
        String inboundProto = trustForwardedFor ? request.getHeader("X-Forwarded-Proto") : null;
        headers.add(new HeaderPair("X-Forwarded-Proto", inboundProto == null ? request.getScheme() : inboundProto));
        String host = request.getHeader("Host");
        if (host != null) {
            headers.add(new HeaderPair("X-Forwarded-Host", host));
        }
        headers.add(new HeaderPair("X-Request-ID", requestId));
        headers.add(new HeaderPair("Via", viaValue(joined(request.getHeaders("Via")))));
        if (tenantId != null) {
            headers.add(new HeaderPair("X-Tenant-Id", tenantId));
        }
        return List.copyOf(headers);
    }

    public boolean isHopByHop(String lowerName) {
        return HOP_BY_HOP.contains(lowerName);
    }

    public Set<String> connectionTokens(List<String> connectionHeaderValues) {
        Set<String> tokens = new HashSet<>();
        for (String value : connectionHeaderValues) {
            for (String token : value.split(",")) {
                String trimmed = token.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed);
                }
            }
        }
        return tokens;
    }

    public String viaValue(String existing) {
        String mine = "1.1 " + viaToken;
        return existing == null || existing.isBlank() ? mine : existing + ", " + mine;
    }

    public String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static String joined(java.util.Enumeration<String> values) {
        List<String> list = Collections.list(values);
        return list.isEmpty() ? null : String.join(", ", list);
    }
}
