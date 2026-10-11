package com.sluice.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MockUpstream implements AutoCloseable {

    private final String name;
    private final HttpServer server;
    private final Map<String, LongAdder> hitsByPath = new ConcurrentHashMap<>();
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicLong cacheableVersion = new AtomicLong();

    private MockUpstream(String name, HttpServer server) {
        this.name = name;
        this.server = server;
    }

    public static MockUpstream start(String name, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
        MockUpstream upstream = new MockUpstream(name, server);
        server.createContext("/", upstream::dispatch);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return upstream;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public String name() {
        return name;
    }

    public long hits(String path) {
        LongAdder adder = hitsByPath.get(path);
        return adder == null ? 0 : adder.sum();
    }

    public long totalHits() {
        return hitsByPath.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("/control") && !entry.getKey().equals("/health"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }

    public void setHealthy(boolean value) {
        healthy.set(value);
    }

    public void setFailing(boolean value) {
        failing.set(value);
    }

    public void resetCounters() {
        hitsByPath.clear();
        cacheableVersion.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hitsByPath.computeIfAbsent(path, key -> new LongAdder()).increment();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            if (path.startsWith("/control/")) {
                control(exchange, path, query);
                return;
            }
            if (path.equals("/health")) {
                respond(exchange, healthy.get() ? 200 : 503, healthy.get() ? "ok" : "unhealthy", "text/plain");
                return;
            }
            if (failing.get()) {
                respond(exchange, 503, "{\"upstream\":\"" + name + "\",\"error\":\"failing\"}", "application/json");
                return;
            }
            route(exchange, path, query);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}", "application/json");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        if (path.startsWith("/slow")) {
            sleep(intParam(query, "ms", 1000));
            respond(exchange, 200, "{\"upstream\":\"" + name + "\",\"slow\":true}", "application/json");
        } else if (path.startsWith("/flaky")) {
            double rate = Double.parseDouble(query.getOrDefault("rate", "0.5"));
            boolean fail = ThreadLocalRandom.current().nextDouble() < rate;
            respond(exchange, fail ? 500 : 200, "{\"upstream\":\"" + name + "\",\"flaky\":true}", "application/json");
        } else if (path.startsWith("/status/")) {
            int status = Integer.parseInt(path.substring("/status/".length()));
            respond(exchange, status, "{\"upstream\":\"" + name + "\",\"status\":" + status + "}", "application/json");
        } else if (path.startsWith("/error")) {
            respond(exchange, intParam(query, "status", 500), "{\"upstream\":\"" + name + "\",\"error\":true}", "application/json");
        } else if (path.startsWith("/stream")) {
            stream(exchange, intParam(query, "chunks", 5), intParam(query, "delayMs", 50));
        } else if (path.startsWith("/cacheable")) {
            cacheable(exchange, query);
        } else if (path.startsWith("/big")) {
            big(exchange, intParam(query, "bytes", 1024 * 1024));
        } else {
            echo(exchange, path, query);
        }
    }

    private void control(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        switch (path) {
            case "/control/health" -> {
                healthy.set(Boolean.parseBoolean(query.getOrDefault("up", "true")));
                respond(exchange, 200, "{\"healthy\":" + healthy.get() + "}", "application/json");
            }
            case "/control/fail" -> {
                failing.set(Boolean.parseBoolean(query.getOrDefault("on", "true")));
                respond(exchange, 200, "{\"failing\":" + failing.get() + "}", "application/json");
            }
            case "/control/reset" -> {
                resetCounters();
                respond(exchange, 200, "{\"reset\":true}", "application/json");
            }
            case "/control/hits" -> respond(exchange, 200, "{\"upstream\":\"" + name + "\",\"hits\":" + totalHits() + "}", "application/json");
            default -> respond(exchange, 404, "{\"error\":\"unknown control\"}", "application/json");
        }
    }

    private void echo(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(), String.join(",", values)));
        StringBuilder json = new StringBuilder("{");
        json.append("\"upstream\":\"").append(name).append("\",");
        json.append("\"method\":\"").append(exchange.getRequestMethod()).append("\",");
        json.append("\"path\":\"").append(escape(exchange.getRequestURI().getRawPath())).append("\",");
        json.append("\"query\":\"").append(escape(String.valueOf(exchange.getRequestURI().getRawQuery()))).append("\",");
        json.append("\"bodyLength\":").append(requestBody.length).append(",");
        json.append("\"body\":\"").append(escape(new String(requestBody, StandardCharsets.UTF_8))).append("\",");
        json.append("\"headers\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"").append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append("\"");
        }
        json.append("}}");
        respond(exchange, 200, json.toString(), "application/json");
    }

    private void stream(HttpExchange exchange, int chunks, int delayMs) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.getResponseHeaders().set("X-Upstream", name);
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < chunks; i++) {
                out.write(("chunk-" + i + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(delayMs);
            }
        }
    }

    private void cacheable(HttpExchange exchange, Map<String, String> query) throws IOException {
        long version = cacheableVersion.incrementAndGet();
        int ttl = intParam(query, "ttl", 60);
        sleep(intParam(query, "delayMs", 0));
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=" + ttl);
        respond(exchange, 200, "{\"upstream\":\"" + name + "\",\"version\":" + version + "}", "application/json");
    }

    private void big(HttpExchange exchange, int bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("X-Upstream", name);
        exchange.sendResponseHeaders(200, bytes);
        byte[] block = new byte[8192];
        Arrays.fill(block, (byte) 'x');
        try (OutputStream out = exchange.getResponseBody()) {
            int remaining = bytes;
            while (remaining > 0) {
                int size = Math.min(remaining, block.length);
                out.write(block, 0, size);
                remaining -= size;
            }
        }
    }

    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Upstream", name);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) {
                result.put(pair, "");
            } else {
                result.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }
        return result;
    }

    private static int intParam(Map<String, String> query, String key, int fallback) {
        String value = query.get(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
