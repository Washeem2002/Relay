package com.sluice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sluice.routing.SnapshotHolder;
import com.sluice.schema.ConfigDocument;
import com.sluice.support.Http;
import com.sluice.support.TestEnvironment;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
public abstract class GatewayIntegrationTest {

    protected static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected SnapshotHolder snapshots;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> TestEnvironment.GATEWAY_PORT);
        registry.add("sluice.admin.port", () -> TestEnvironment.ADMIN_PORT);
        registry.add("spring.data.redis.port", () -> TestEnvironment.REDIS_PORT);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.datasource.url", () -> TestEnvironment.DATABASE_URL);
    }

    @BeforeEach
    void resetEnvironment() {
        TestEnvironment.resetUpstreams();
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        Http.Response response = putConfig(ConfigDocument.empty());
        assertThat(response.status()).isEqualTo(200);
    }

    protected Http.Response admin(String method, String path, Object body) {
        try {
            String json = body == null ? null : body instanceof String text ? text : mapper.writeValueAsString(body);
            return Http.send(method, TestEnvironment.adminUrl(path), json, "X-Admin-Token", ADMIN_TOKEN,
                    "Content-Type", "application/json");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Http.Response putConfig(ConfigDocument document) {
        return admin("PUT", "/admin/config", document);
    }

    protected Http.Response applyConfig(ConfigDocument document) {
        Http.Response response = putConfig(document);
        assertThat(response.status()).as("config rejected: %s", response.body()).isEqualTo(200);
        return response;
    }

    protected JsonNode json(Http.Response response) {
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("not json: " + response.body(), e);
        }
    }

    protected Http.Response gateway(String path, String... headers) {
        return Http.get(TestEnvironment.gatewayUrl(path), headers);
    }

    protected String upstreamOf(Http.Response response) {
        return json(response).path("upstream").asText();
    }
}
