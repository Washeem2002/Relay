package com.sluice.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sluice.schema.JwtAlgorithm;
import com.sluice.schema.JwtConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class JwtVerifier {

    private static final long CLOCK_SKEW_MILLIS = 30_000;

    public record Claims(String subject, String tenant) {
    }

    private final JwtConfig config;
    private final Clock clock;
    private final JWSAlgorithm algorithm;
    private final JWSVerifier macVerifier;
    private final JWKSet jwkSet;

    private JwtVerifier(JwtConfig config, Clock clock, JWSVerifier macVerifier, JWKSet jwkSet) {
        this.config = config;
        this.clock = clock;
        this.algorithm = config.algorithm() == JwtAlgorithm.HS256 ? JWSAlgorithm.HS256 : JWSAlgorithm.RS256;
        this.macVerifier = macVerifier;
        this.jwkSet = jwkSet;
    }

    public static JwtVerifier create(JwtConfig config, Clock clock, Function<String, String> environment)
            throws IOException, ParseException, JOSEException {
        if (config.algorithm() == JwtAlgorithm.HS256) {
            String secret = environment.apply(config.secretEnv());
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("environment variable " + config.secretEnv()
                        + " must hold an HS256 secret of at least 32 bytes");
            }
            return new JwtVerifier(config, clock, new MACVerifier(secret.getBytes(StandardCharsets.UTF_8)), null);
        }
        JWKSet set = JWKSet.load(new File(config.jwksPath()));
        return new JwtVerifier(config, clock, null, set);
    }

    public Optional<Claims> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!algorithm.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!jwt.verify(verifierFor(jwt))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!claimsValid(claims)) {
                return Optional.empty();
            }
            Object tenant = claims.getClaim(config.tenantClaim());
            return Optional.of(new Claims(claims.getSubject(), tenant == null ? claims.getSubject() : tenant.toString()));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private JWSVerifier verifierFor(SignedJWT jwt) throws JOSEException {
        if (macVerifier != null) {
            return macVerifier;
        }
        String keyId = jwt.getHeader().getKeyID();
        JWK key = keyId == null
                ? (jwkSet.getKeys().size() == 1 ? jwkSet.getKeys().get(0) : null)
                : jwkSet.getKeyByKeyId(keyId);
        if (!(key instanceof RSAKey rsaKey)) {
            throw new JOSEException("no matching rsa key");
        }
        return new RSASSAVerifier(rsaKey.toRSAPublicKey());
    }

    private boolean claimsValid(JWTClaimsSet claims) {
        long now = clock.millis();
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.getTime() + CLOCK_SKEW_MILLIS < now) {
            return false;
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.getTime() - CLOCK_SKEW_MILLIS > now) {
            return false;
        }
        if (config.issuer() != null && !config.issuer().equals(claims.getIssuer())) {
            return false;
        }
        if (config.audience() != null) {
            List<String> audience = claims.getAudience();
            return audience != null && audience.contains(config.audience());
        }
        return true;
    }
}
