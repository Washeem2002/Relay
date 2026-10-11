package com.sluice.schema;

public record AuthConfig(AuthMode mode, JwtConfig jwt) {

    public AuthConfig {
        if (mode == null) {
            mode = AuthMode.NONE;
        }
    }

    public static AuthConfig none() {
        return new AuthConfig(AuthMode.NONE, null);
    }
}
