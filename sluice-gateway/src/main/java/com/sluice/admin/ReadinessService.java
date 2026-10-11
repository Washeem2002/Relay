package com.sluice.admin;

import com.sluice.config.ConfigReloader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadinessService {

    public record Report(boolean ready, Map<String, Boolean> checks) {
    }

    private final ConfigReloader reloader;
    private final RedisConnectionFactory redis;
    private final JdbcTemplate jdbc;
    private volatile boolean shuttingDown;

    public ReadinessService(ConfigReloader reloader, RedisConnectionFactory redis, JdbcTemplate jdbc) {
        this.reloader = reloader;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @EventListener
    public void onShutdown(ContextClosedEvent event) {
        shuttingDown = true;
    }

    public Report check() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("running", !shuttingDown);
        checks.put("configLoaded", reloader.isInitialLoadComplete());
        checks.put("redis", redisReachable());
        checks.put("database", databaseReachable());
        boolean ready = checks.values().stream().allMatch(Boolean::booleanValue);
        return new Report(ready, checks);
    }

    private boolean redisReachable() {
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean databaseReachable() {
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
