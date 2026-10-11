package com.sluice.ratelimit;

import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final RedisScript<List<Long>> slidingWindowScript = script("lua/sliding_window.lua");
    private final RedisScript<List<Long>> tokenBucketScript = script("lua/token_bucket.lua");

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public RateLimitDecision check(String key, RateLimitPolicy policy, String scope) {
        return check(key, policy, scope, 1);
    }

    public RateLimitDecision check(String key, RateLimitPolicy policy, String scope, int cost) {
        List<Long> result = switch (policy.algorithm()) {
            case SLIDING_WINDOW -> redis.execute(slidingWindowScript, List.of(key),
                    String.valueOf(policy.limit()),
                    String.valueOf(policy.windowSeconds() * 1000L),
                    String.valueOf(cost));
            case TOKEN_BUCKET -> redis.execute(tokenBucketScript, List.of(key),
                    String.valueOf(policy.burst()),
                    String.valueOf((double) policy.limit() / policy.windowSeconds()),
                    String.valueOf(cost));
        };
        if (result == null || result.size() < 4) {
            throw new IllegalStateException("rate limit script returned no result");
        }
        boolean allowed = result.get(0) == 1L;
        long limit = policy.algorithm() == RateLimitAlgorithm.TOKEN_BUCKET
                ? policy.burst() : policy.limit();
        long resetEpochSeconds = (clock.millis() + result.get(2) + 999) / 1000;
        long retryAfterSeconds = allowed ? 0 : Math.max(1, (result.get(3) + 999) / 1000);
        return new RateLimitDecision(allowed, limit, result.get(1), resetEpochSeconds, retryAfterSeconds, scope);
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<List<Long>> script(String path) {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }
}
