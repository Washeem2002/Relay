package com.sluice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class StartupChecks {

    private static final int MIN_SECRET_LENGTH = 16;

    private final SluiceProperties properties;

    public StartupChecks(SluiceProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (properties.admin().token().isBlank()) {
            throw new IllegalStateException("SLUICE_ADMIN_TOKEN must be set");
        }
        if (properties.security().apiKeySecret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("SLUICE_API_KEY_SECRET must be at least " + MIN_SECRET_LENGTH
                    + " characters");
        }
        if (properties.admin().port() <= 0) {
            throw new IllegalStateException("SLUICE_ADMIN_PORT must be a positive port number");
        }
    }
}
