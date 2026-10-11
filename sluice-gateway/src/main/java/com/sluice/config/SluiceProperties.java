package com.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sluice")
public record SluiceProperties(
        @DefaultValue Admin admin,
        @DefaultValue Security security,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Config config,
        @DefaultValue Gateway gateway,
        @DefaultValue Cache cache) {

    public enum FailMode {
        OPEN,
        CLOSED
    }

    public record Admin(@DefaultValue("9090") int port, @DefaultValue("") String token) {
    }

    public record Security(
            @DefaultValue("") String apiKeySecret,
            @DefaultValue("10485760") long maxRequestBodyBytes,
            @DefaultValue("60000") int maxUpstreamTimeoutMs,
            @DefaultValue("false") boolean trustForwardedFor) {
    }

    public record RateLimit(
            @DefaultValue("OPEN") FailMode failMode,
            @DefaultValue("sluice:rl:") String keyPrefix) {
    }

    public record Config(
            @DefaultValue("5000") long pollIntervalMs,
            @DefaultValue("sluice:config:updates") String channel,
            @DefaultValue("") String bootstrapPath) {
    }

    public record Gateway(
            @DefaultValue("sluice") String viaToken,
            @DefaultValue("500") long healthTickMs,
            @DefaultValue("5000") long canaryEvalIntervalMs,
            @DefaultValue("30000") long retiredPoolGraceMs) {
    }

    public record Cache(
            @DefaultValue("sluice:cache:") String keyPrefix,
            @DefaultValue("10000") long coalesceWaitMs) {
    }
}
