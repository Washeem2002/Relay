package com.sluice.support;

import java.io.IOException;
import java.net.ServerSocket;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

public final class TestRedis {

    private static RedisServer server;
    private static int port;

    private TestRedis() {
    }

    public static synchronized int start() {
        if (server == null) {
            try {
                port = freePort();
                server = RedisServer.newRedisServer().port(port).setting("maxmemory 128M").build();
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.stop();
                    } catch (IOException ignored) {
                        port = 0;
                    }
                }));
            } catch (IOException e) {
                throw new IllegalStateException("cannot start embedded redis", e);
            }
        }
        return port;
    }

    public static StringRedisTemplate template() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", start());
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
