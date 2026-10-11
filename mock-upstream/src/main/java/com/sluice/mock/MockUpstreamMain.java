package com.sluice.mock;

import java.io.IOException;

public final class MockUpstreamMain {

    private MockUpstreamMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String name = System.getenv().getOrDefault("INSTANCE_NAME", "mock");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9000"));
        MockUpstream upstream = MockUpstream.start(name, port);
        Runtime.getRuntime().addShutdownHook(new Thread(upstream::close));
        System.out.println("mock upstream " + name + " listening on " + upstream.port());
        Thread.currentThread().join();
    }
}
