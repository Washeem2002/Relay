package com.sluice.support;

import com.sluice.config.SluiceProperties;

public final class TestProps {

    private TestProps() {
    }

    public static SluiceProperties create() {
        return new SluiceProperties(
                new SluiceProperties.Admin(9090, "admin-token"),
                new SluiceProperties.Security("test-secret-with-enough-length", 10_485_760, 60_000, false),
                new SluiceProperties.RateLimit(SluiceProperties.FailMode.OPEN, "sluice:rl:"),
                new SluiceProperties.Config(5_000, "sluice:config:updates", ""),
                new SluiceProperties.Gateway("sluice", 500, 5_000, 1_000),
                new SluiceProperties.Cache("sluice:cache:", 10_000));
    }
}
