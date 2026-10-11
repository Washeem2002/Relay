package com.sluice.config;

import com.sluice.observability.SluiceMetrics;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.routing.SnapshotCompiler;
import com.sluice.routing.SnapshotHolder;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConfigReloader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigReloader.class);

    public enum Outcome {
        APPLIED,
        UNCHANGED,
        FAILED
    }

    private final ConfigService configService;
    private final SnapshotHolder holder;
    private final SnapshotCompiler compiler;
    private final SluiceMetrics metrics;
    private final StringRedisTemplate redis;
    private final SluiceProperties properties;
    private volatile boolean initialLoadComplete;
    private volatile String lastError;
    private volatile long failedVersion = -1;

    public ConfigReloader(ConfigService configService, SnapshotHolder holder, SnapshotCompiler compiler,
                          SluiceMetrics metrics, StringRedisTemplate redis, SluiceProperties properties) {
        this.configService = configService;
        this.holder = holder;
        this.compiler = compiler;
        this.metrics = metrics;
        this.redis = redis;
        this.properties = properties;
    }

    public boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized Outcome reloadLatest() {
        Optional<ConfigService.VersionedConfig> latest;
        try {
            latest = configService.latest();
        } catch (RuntimeException e) {
            LOG.warn("config store unreachable, keeping version {}: {}", holder.version(), e.toString());
            return Outcome.FAILED;
        }
        initialLoadComplete = true;
        if (latest.isEmpty() || latest.get().version() == holder.version()) {
            return Outcome.UNCHANGED;
        }
        ConfigService.VersionedConfig config = latest.get();
        if (config.version() == failedVersion) {
            return Outcome.FAILED;
        }
        try {
            RoutingSnapshot snapshot = compiler.compile(config.version(), config.document());
            holder.install(snapshot);
            compiler.prune(snapshot);
            metrics.configVersion(config.version());
            lastError = null;
            failedVersion = -1;
            LOG.info("applied config version {}", config.version());
            return Outcome.APPLIED;
        } catch (RuntimeException e) {
            failedVersion = config.version();
            lastError = "version " + config.version() + " rejected: " + e.getMessage();
            metrics.configReloadFailed();
            LOG.error("config version {} rejected, keeping version {}: {}", config.version(), holder.version(),
                    e.getMessage());
            return Outcome.FAILED;
        }
    }

    public synchronized Outcome forceReload() {
        failedVersion = -1;
        return reloadLatest();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        reloadLatest();
        try {
            redis.convertAndSend(properties.config().channel(), String.valueOf(event.version()));
        } catch (RuntimeException e) {
            LOG.warn("could not publish config version {} to redis, peers will catch up by polling: {}",
                    event.version(), e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${sluice.config.poll-interval-ms:5000}",
            initialDelayString = "${sluice.config.poll-interval-ms:5000}")
    public void poll() {
        reloadLatest();
    }
}
