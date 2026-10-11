package com.sluice.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PathNormalizer {

    private PathNormalizer() {
    }

    public static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        if (path == null) {
            return result;
        }
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    public static String stripSegments(String rawPath, int count) {
        int index = 0;
        int consumed = 0;
        int length = rawPath.length();
        while (consumed < count && index < length) {
            while (index < length && rawPath.charAt(index) == '/') {
                index++;
            }
            while (index < length && rawPath.charAt(index) != '/') {
                index++;
            }
            consumed++;
        }
        return rawPath.substring(index);
    }

    public static boolean containsTraversal(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        for (String segment : rawPath.split("/")) {
            String lowered = segment.toLowerCase(Locale.ROOT)
                    .replace("%2e", ".")
                    .replace("%2f", "/")
                    .replace("%5c", "\\");
            if (lowered.equals("..") || lowered.equals(".") || lowered.contains("/") || lowered.contains("\\")) {
                return true;
            }
        }
        return false;
    }

    public static String hostWithoutPort(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(0, end + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }
}
