package com.sluice.balancing;

import com.sluice.infra.GuardedDnsResolver;
import com.sluice.resilience.Bulkhead;
import com.sluice.schema.PoolConfig;
import java.io.IOException;
import java.net.URI;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

public final class PoolRuntime implements AutoCloseable {

    private final String fingerprint;
    private final Bulkhead bulkhead;
    private final CloseableHttpClient client;

    public PoolRuntime(PoolConfig config) {
        this.fingerprint = fingerprint(config);
        this.bulkhead = new Bulkhead(config.maxConcurrency(), config.queueTimeoutMs());
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnections())
                .setDnsResolver(new GuardedDnsResolver(config.allowPrivateTargets()))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeoutMs()))
                        .setSocketTimeout(Timeout.ofSeconds(60))
                        .setTimeToLive(TimeValue.ofMinutes(5))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(config.queueTimeoutMs(), 1_000)))
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .disableContentCompression()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    public static String fingerprint(PoolConfig config) {
        return config.maxConnections() + "|" + config.maxConcurrency() + "|" + config.queueTimeoutMs() + "|"
                + config.connectTimeoutMs() + "|" + config.allowPrivateTargets();
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Bulkhead bulkhead() {
        return bulkhead;
    }

    public CloseableHttpClient client() {
        return client;
    }

    public boolean probe(URI base, String path, int timeoutMs) {
        String root = base.toString().endsWith("/")
                ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        HttpGet request = new HttpGet(URI.create(root + path));
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build());
        try {
            return client.execute(request, response -> {
                EntityUtils.consumeQuietly(response.getEntity());
                return response.getCode() >= 200 && response.getCode() < 300;
            });
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close(CloseMode.GRACEFUL);
    }
}
