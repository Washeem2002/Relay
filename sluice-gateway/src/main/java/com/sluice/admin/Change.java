package com.sluice.admin;

import com.sluice.config.ConfigService;

public record Change<T>(long configVersion, boolean changed, T resource) {

    public static <T> Change<T> of(ConfigService.MutationResult result, T resource) {
        return new Change<>(result.config().version(), result.changed(), resource);
    }
}
