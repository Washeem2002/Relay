package com.sluice.gateway.middleware;

import com.sluice.config.SluiceProperties;
import com.sluice.gateway.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestLimits {

    private final long maxBodyBytes;

    public RequestLimits(SluiceProperties properties) {
        this.maxBodyBytes = properties.security().maxRequestBodyBytes();
    }

    public long maxBodyBytes() {
        return maxBodyBytes;
    }

    public void check(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            throw new GatewayException(413, "payload_too_large",
                    "request body of " + declared + " bytes exceeds the limit of " + maxBodyBytes);
        }
    }
}
