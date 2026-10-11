package com.sluice.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

public class GatewayException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Map<String, String> headers;

    public GatewayException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public GatewayException(int status, String code, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.headers = new LinkedHashMap<>(headers);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
