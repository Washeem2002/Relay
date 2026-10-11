package com.sluice.routing;

import java.util.function.Function;

public final class StickyKey {

    private StickyKey() {
    }

    public static String resolve(String spec, Function<String, String> header, Function<String, String> cookie,
                                 String clientIp) {
        String value = null;
        if (spec.equals("api_key")) {
            value = header.apply("X-API-Key");
        } else if (spec.startsWith("header:")) {
            value = header.apply(spec.substring(7));
        } else if (spec.startsWith("cookie:")) {
            value = cookie.apply(spec.substring(7));
        }
        return value == null || value.isEmpty() ? clientIp : value;
    }
}
