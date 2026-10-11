package com.sluice.config;

import java.util.List;

public class ConfigValidationException extends RuntimeException {

    private final transient List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("invalid configuration: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
