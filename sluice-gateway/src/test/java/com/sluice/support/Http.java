package com.sluice.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Http {

    public record Response(int status, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Http() {
    }

    public static Response send(String method, String url, String body, String... headerPairs) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
            for (int i = 0; i < headerPairs.length; i += 2) {
                builder.header(headerPairs[i], headerPairs[i + 1]);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> response = CLIENT.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(response.headers().map());
            return new Response(response.statusCode(), headers, response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static Response get(String url, String... headerPairs) {
        return send("GET", url, null, headerPairs);
    }

    public static HttpClient client() {
        return CLIENT;
    }
}
