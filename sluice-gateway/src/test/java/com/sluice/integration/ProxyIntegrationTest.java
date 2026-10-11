package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sluice.schema.CircuitBreakerConfig;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.PoolConfig;
import com.sluice.schema.RetryConfig;
import com.sluice.schema.TargetConfig;
import com.sluice.support.Configs;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProxyIntegrationTest extends GatewayIntegrationTest {

    private ConfigDocument echoConfig() {
        return Configs.document(List.of(Configs.pool("echo-pool", TestEnvironment.UPSTREAM_A.baseUrl())),
                List.of(Configs.route("echo", "/echo", "echo-pool", true, 0, RetryConfig.defaults(), null, null)));
    }

    @Test
    void proxiesRequestStripsPrefixAndAddsForwardingHeaders() {
        applyConfig(echoConfig());
        Http.Response response = gateway("/echo/hello?x=1&y=2", "X-Request-ID", "trace-123", "X-Custom", "kept",
                "X-Forwarded-For", "6.6.6.6");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("upstream").asText()).isEqualTo("upstream-a");
        assertThat(body.path("path").asText()).isEqualTo("/hello");
        assertThat(body.path("query").asText()).isEqualTo("x=1&y=2");
        JsonNode headers = body.path("headers");
        assertThat(headers.path("x-request-id").asText()).isEqualTo("trace-123");
        assertThat(response.header("X-Request-ID")).isEqualTo("trace-123");
        assertThat(headers.path("x-forwarded-for").asText()).isEqualTo("127.0.0.1");
        assertThat(headers.path("x-forwarded-proto").asText()).isEqualTo("http");
        assertThat(headers.path("x-forwarded-host").asText()).startsWith("127.0.0.1");
        assertThat(headers.path("via").asText()).contains("1.1 sluice");
        assertThat(headers.path("x-custom").asText()).isEqualTo("kept");
        assertThat(response.header("Via")).contains("1.1 sluice");
    }

    @Test
    void generatesRequestIdWhenMissingAndRejectsUnsafeOnes() {
        applyConfig(echoConfig());
        Http.Response generated = gateway("/echo/a");
        assertThat(generated.header("X-Request-ID")).hasSize(36);
        assertThat(json(generated).path("headers").path("x-request-id").asText()).isEqualTo(generated.header("X-Request-ID"));
        Http.Response unsafe = gateway("/echo/a", "X-Request-ID", "bad id with spaces");
        assertThat(unsafe.header("X-Request-ID")).isNotEqualTo("bad id with spaces").hasSize(36);
    }

    @Test
    void forwardsRequestBodyAndMethod() {
        applyConfig(echoConfig());
        Http.Response response = Http.send("POST", TestEnvironment.gatewayUrl("/echo/submit"), "hello gateway",
                "Content-Type", "text/plain");
        JsonNode body = json(response);
        assertThat(response.status()).isEqualTo(200);
        assertThat(body.path("method").asText()).isEqualTo("POST");
        assertThat(body.path("body").asText()).isEqualTo("hello gateway");
        assertThat(body.path("bodyLength").asInt()).isEqualTo(13);
    }

    @Test
    void stripsHopByHopHeadersIncludingThoseNamedInConnection() throws IOException {
        applyConfig(echoConfig());
        String raw = rawRequest("GET /echo/raw HTTP/1.1\r\nHost: localhost\r\nConnection: close, X-Secret-Hop\r\n"
                + "X-Secret-Hop: classified\r\nX-Visible: yes\r\nTE: trailers\r\n\r\n");
        String payload = raw.substring(raw.indexOf("{"));
        JsonNode headers = mapper.readTree(payload).path("headers");
        assertThat(headers.has("x-secret-hop")).isFalse();
        assertThat(headers.has("te")).isFalse();
        assertThat(headers.path("x-visible").asText()).isEqualTo("yes");
    }

    @Test
    void streamsResponseBodyWithoutBufferingTheWholeThing() throws Exception {
        applyConfig(Configs.document(List.of(Configs.pool("stream-pool", TestEnvironment.UPSTREAM_A.baseUrl())),
                List.of(Configs.route("stream", "/stream", "stream-pool"))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestEnvironment.gatewayUrl("/stream?chunks=5&delayMs=300")))
                .GET().build();
        long start = System.nanoTime();
        HttpResponse<InputStream> response = Http.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            byte[] first = new byte[7];
            int read = body.readNBytes(first, 0, first.length);
            long firstChunkMillis = (System.nanoTime() - start) / 1_000_000;
            String rest = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            long totalMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(read).isEqualTo(7);
            assertThat(new String(first, StandardCharsets.UTF_8)).isEqualTo("chunk-0");
            assertThat(rest).contains("chunk-4");
            assertThat(firstChunkMillis).isLessThan(900);
            assertThat(totalMillis).isGreaterThanOrEqualTo(1000);
        }
    }

    @Test
    void largeResponseIsPassedThroughIntact() {
        applyConfig(Configs.document(List.of(Configs.pool("big-pool", TestEnvironment.UPSTREAM_A.baseUrl())),
                List.of(Configs.route("big", "/big", "big-pool"))));
        Http.Response response = gateway("/big?bytes=3000000");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).hasSize(3_000_000);
        assertThat(response.header("Content-Length")).isEqualTo("3000000");
    }

    @Test
    void unmatchedRequestReturnsConsistentJson404() {
        applyConfig(echoConfig());
        Http.Response response = gateway("/nothing/here", "X-Request-ID", "req-404");
        assertThat(response.status()).isEqualTo(404);
        JsonNode error = json(response).path("error");
        assertThat(error.path("code").asText()).isEqualTo("route_not_found");
        assertThat(error.path("status").asInt()).isEqualTo(404);
        assertThat(error.path("requestId").asText()).isEqualTo("req-404");
        assertThat(response.header("Content-Type")).contains("application/json");
    }

    @Test
    void unreachableUpstreamIsA502() {
        PoolConfig dead = Configs.pool("dead", "http://127.0.0.1:1");
        applyConfig(Configs.document(List.of(dead), List.of(Configs.route("dead", "/dead", "dead"))));
        Http.Response response = gateway("/dead/x");
        assertThat(response.status()).isEqualTo(502);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("upstream_unavailable");
    }

    @Test
    void slowUpstreamIsA504WhenRouteTimeoutElapses() {
        applyConfig(Configs.document(List.of(Configs.pool("slow-pool", TestEnvironment.UPSTREAM_A.baseUrl())),
                List.of(Configs.route("slow", "/slow", "slow-pool", false, 300, RetryConfig.defaults(), null, null))));
        long start = System.nanoTime();
        Http.Response response = gateway("/slow?ms=3000");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(response.status()).isEqualTo(504);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("upstream_timeout");
        assertThat(elapsedMillis).isLessThan(2_500);
    }

    @Test
    void blocksPathTraversalAndOversizedBodies() {
        applyConfig(echoConfig());
        Http.Response traversal = gateway("/echo/%2e%2e/admin");
        assertThat(traversal.status()).isEqualTo(400);
        String big = "x".repeat(2 * 1024 * 1024);
        Http.Response oversized = Http.send("POST", TestEnvironment.gatewayUrl("/echo/upload"), big);
        assertThat(oversized.status()).isEqualTo(413);
        assertThat(json(oversized).path("error").path("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void oversizedChunkedBodyIsRejectedWhileStreaming() throws Exception {
        applyConfig(echoConfig());
        byte[] chunk = new byte[64 * 1024];
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestEnvironment.gatewayUrl("/echo/upload")))
                .POST(HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.InputStream() {
                            private long sent;

                            @Override
                            public int read() {
                                return sent++ < 3L * 1024 * 1024 ? 'a' : -1;
                            }

                            @Override
                            public int read(byte[] buffer, int offset, int length) {
                                if (sent >= 3L * 1024 * 1024) {
                                    return -1;
                                }
                                int size = (int) Math.min(Math.min(length, chunk.length), 3L * 1024 * 1024 - sent);
                                java.util.Arrays.fill(buffer, offset, offset + size, (byte) 'a');
                                sent += size;
                                return size;
                            }
                        })))
                .build();
        HttpResponse<String> response = Http.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void adminApiIsNotReachableOnTheGatewayPortAndNeedsAToken() {
        applyConfig(echoConfig());
        assertThat(gateway("/admin/routes").status()).isEqualTo(404);
        assertThat(Http.get(TestEnvironment.adminUrl("/admin/routes")).status()).isEqualTo(401);
        assertThat(Http.get(TestEnvironment.adminUrl("/admin/routes"), "X-Admin-Token", "wrong").status()).isEqualTo(401);
        assertThat(admin("GET", "/admin/routes", null).status()).isEqualTo(200);
        assertThat(Http.get(TestEnvironment.adminUrl("/echo/hello")).status()).isEqualTo(404);
    }

    @Test
    void exposesHealthReadinessAndMetricsOnTheAdminPort() {
        applyConfig(echoConfig());
        gateway("/echo/metrics-check");
        assertThat(Http.get(TestEnvironment.adminUrl("/healthz")).status()).isEqualTo(200);
        Http.Response ready = Http.get(TestEnvironment.adminUrl("/readyz"));
        assertThat(ready.status()).isEqualTo(200);
        assertThat(json(ready).path("checks").path("redis").asBoolean()).isTrue();
        assertThat(json(ready).path("checks").path("database").asBoolean()).isTrue();
        Http.Response metrics = Http.get(TestEnvironment.adminUrl("/metrics"));
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body()).contains("sluice_requests_total{route=\"echo\"")
                .contains("sluice_request_duration_seconds_bucket")
                .contains("sluice_config_version")
                .contains("sluice_circuit_state{pool=\"echo-pool\"")
                .contains("sluice_upstream_duration_seconds");
    }

    @Test
    void rejectsPrivateTargetsUnlessExplicitlyAllowed() {
        PoolConfig blocked = new PoolConfig("blocked", null, null, List.of(new TargetConfig("t1", "http://127.0.0.1:9", 1)),
                0, 0, 0, 0, null, 0, 0, 0, 0, false, CircuitBreakerConfig.defaults());
        Http.Response response = putConfig(Configs.document(List.of(blocked), List.of()));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("private or reserved");
    }

    private String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", TestEnvironment.GATEWAY_PORT)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8));
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
