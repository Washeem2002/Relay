package com.sluice.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class CacheKeyBuilder {

    private CacheKeyBuilder() {
    }

    public static String build(String method, String host, String path, String rawQuery, List<String> varyHeaders,
                               Function<String, String> headerLookup) {
        return prefixOf(host, path) + "|" + normalizeQuery(rawQuery) + "|" + method + "|"
                + varyPart(varyHeaders, headerLookup);
    }

    public static String prefixOf(String host, String path) {
        return host.toLowerCase(Locale.ROOT) + "|" + path;
    }

    public static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (!pair.isEmpty()) {
                pairs.add(normalizePercentEncoding(pair));
            }
        }
        Collections.sort(pairs);
        return String.join("&", pairs);
    }

    public static String escapeGlob(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String varyPart(List<String> varyHeaders, Function<String, String> headerLookup) {
        List<String> names = new ArrayList<>();
        for (String header : varyHeaders) {
            names.add(header.toLowerCase(Locale.ROOT));
        }
        Collections.sort(names);
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            String value = headerLookup.apply(name);
            out.append(name).append(':').append(value == null ? "" : value.trim().replaceAll("\\s+", " ")).append(';');
        }
        return out.toString();
    }

    private static String normalizePercentEncoding(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                int decoded = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                if (isUnreserved(decoded)) {
                    out.append((char) decoded);
                } else {
                    out.append('%').append(value.substring(i + 1, i + 3).toUpperCase(Locale.ROOT));
                }
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
