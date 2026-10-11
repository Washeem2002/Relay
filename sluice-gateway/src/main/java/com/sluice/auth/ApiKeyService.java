package com.sluice.auth;

import com.sluice.config.SluiceProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk_";
    private static final int PREFIX_DISPLAY_LENGTH = 11;

    private final SecretKeySpec secret;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(SluiceProperties properties) {
        String configured = properties.security().apiKeySecret();
        if (configured.isBlank()) {
            throw new IllegalStateException("SLUICE_API_KEY_SECRET must be set");
        }
        this.secret = new SecretKeySpec(configured.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String generateKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hmac unavailable", e);
        }
    }

    public String displayPrefix(String key) {
        return key.substring(0, Math.min(PREFIX_DISPLAY_LENGTH, key.length()));
    }
}
