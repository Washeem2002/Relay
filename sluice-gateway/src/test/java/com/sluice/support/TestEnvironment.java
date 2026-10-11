package com.sluice.support;

import com.sluice.mock.MockUpstream;
import java.io.IOException;
import java.util.UUID;

public final class TestEnvironment {

    public static final int GATEWAY_PORT;
    public static final int ADMIN_PORT;
    public static final int REDIS_PORT;
    public static final String DATABASE_URL = "jdbc:h2:mem:sluice-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    public static final MockUpstream UPSTREAM_A;
    public static final MockUpstream UPSTREAM_B;
    public static final MockUpstream UPSTREAM_C;

    static {
        try {
            REDIS_PORT = TestRedis.start();
            GATEWAY_PORT = TestRedis.freePort();
            ADMIN_PORT = TestRedis.freePort();
            UPSTREAM_A = MockUpstream.start("upstream-a", 0);
            UPSTREAM_B = MockUpstream.start("upstream-b", 0);
            UPSTREAM_C = MockUpstream.start("upstream-c", 0);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                UPSTREAM_A.close();
                UPSTREAM_B.close();
                UPSTREAM_C.close();
            }));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestEnvironment() {
    }

    public static String gatewayUrl(String path) {
        return "http://127.0.0.1:" + GATEWAY_PORT + path;
    }

    public static String adminUrl(String path) {
        return "http://127.0.0.1:" + ADMIN_PORT + path;
    }

    public static void resetUpstreams() {
        for (MockUpstream upstream : new MockUpstream[] {UPSTREAM_A, UPSTREAM_B, UPSTREAM_C}) {
            upstream.setFailing(false);
            upstream.setHealthy(true);
            upstream.resetCounters();
        }
    }
}
