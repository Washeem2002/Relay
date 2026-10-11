package com.sluice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.support.TestRedis;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimiterTest {

    private static RateLimiter limiter;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        redis = TestRedis.template();
        limiter = new RateLimiter(redis, Clock.systemUTC());
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void slidingWindowAllowsUpToLimitThenRejectsWithRetryAfter() {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 5, 60, 0);
        String key = key();
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = limiter.check(key, policy, "t");
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(4 - i);
            assertThat(decision.limit()).isEqualTo(5);
        }
        RateLimitDecision rejected = limiter.check(key, policy, "t");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 120L);
        assertThat(rejected.resetEpochSeconds()).isGreaterThan(System.currentTimeMillis() / 1000 - 1);
        assertThat(rejected.scope()).isEqualTo("t");
    }

    @Test
    void slidingWindowRecoversAfterWindowsPass() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 3, 1, 0);
        String key = key();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(key, policy, "t").allowed()).isTrue();
        }
        assertThat(limiter.check(key, policy, "t").allowed()).isFalse();
        Thread.sleep(2_100);
        assertThat(limiter.check(key, policy, "t").allowed()).isTrue();
    }

    @Test
    void slidingWindowWeighsPreviousWindowSmoothly() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 10, 2, 0);
        String key = key();
        long intoWindow = System.currentTimeMillis() % 2_000;
        Thread.sleep(2_000 - intoWindow + 100);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.check(key, policy, "t").allowed()).isTrue();
        }
        assertThat(limiter.check(key, policy, "t").allowed()).isFalse();
        Thread.sleep(2_800);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.check(key, policy, "t").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(2, 6);
    }

    @Test
    void slidingWindowRejectionDoesNotConsumeQuota() {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 2, 60, 0);
        String key = key();
        limiter.check(key, policy, "t");
        limiter.check(key, policy, "t");
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.check(key, policy, "t").allowed()).isFalse();
        }
        assertThat(redis.opsForHash().get(key, "c")).isEqualTo("2");
    }

    @Test
    void slidingWindowCostLargerThanLimitIsRejected() {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 2, 60, 0);
        RateLimitDecision decision = limiter.check(key(), policy, "t", 5);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tokenBucketAllowsBurstThenRefills() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 10, 1, 3);
        String key = key();
        for (int i = 0; i < 3; i++) {
            RateLimitDecision decision = limiter.check(key, policy, "t");
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.limit()).isEqualTo(3);
        }
        RateLimitDecision rejected = limiter.check(key, policy, "t");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
        Thread.sleep(350);
        assertThat(limiter.check(key, policy, "t").allowed()).isTrue();
    }

    @Test
    void tokenBucketNeverExceedsCapacity() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 100, 1, 2);
        String key = key();
        limiter.check(key, policy, "t");
        Thread.sleep(300);
        RateLimitDecision decision = limiter.check(key, policy, "t");
        assertThat(decision.remaining()).isLessThanOrEqualTo(1);
    }

    @Test
    void limitsAreEnforcedAtomicallyUnderConcurrency() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 50, 60, 0);
        String key = key();
        int threads = 16;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        if (limiter.check(key, policy, "t").allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    done.countDown();
                });
            }
            done.await();
        }
        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    void differentKeysAreIndependent() {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 1, 60, 0);
        assertThat(limiter.check(key(), policy, "t").allowed()).isTrue();
        assertThat(limiter.check(key(), policy, "t").allowed()).isTrue();
    }
}
