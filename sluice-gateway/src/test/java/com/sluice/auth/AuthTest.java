package com.sluice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sluice.config.SluiceProperties;
import com.sluice.schema.ApiKeyRecord;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.JwtAlgorithm;
import com.sluice.schema.JwtConfig;
import com.sluice.schema.PlanConfig;
import com.sluice.schema.RateLimitAlgorithm;
import com.sluice.schema.RateLimitPolicy;
import com.sluice.schema.TenantConfig;
import com.sluice.support.MutableClock;
import com.sluice.support.TestProps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";

    @Test
    void apiKeysAreHashedWithServerSecretAndNeverEqualPlaintext() {
        ApiKeyService service = new ApiKeyService(TestProps.create());
        String key = service.generateKey();
        assertThat(key).startsWith("sk_").hasSizeGreaterThan(40);
        assertThat(service.generateKey()).isNotEqualTo(key);
        String hash = service.hash(key);
        assertThat(hash).hasSize(64).isNotEqualTo(key).doesNotContain(key);
        assertThat(service.hash(key)).isEqualTo(hash);
        assertThat(service.hash(key + "x")).isNotEqualTo(hash);
        assertThat(service.displayPrefix(key)).hasSize(11);
        assertThat(service.displayPrefix("sk_a")).isEqualTo("sk_a");
    }

    @Test
    void hashDependsOnTheServerSecret() {
        SluiceProperties base = TestProps.create();
        SluiceProperties other = new SluiceProperties(base.admin(),
                new SluiceProperties.Security("a-completely-different-secret", 1, 1, false), base.rateLimit(),
                base.config(), base.gateway(), base.cache());
        assertThat(new ApiKeyService(base).hash("sk_same")).isNotEqualTo(new ApiKeyService(other).hash("sk_same"));
    }

    @Test
    void blankServerSecretIsRejected() {
        SluiceProperties base = TestProps.create();
        SluiceProperties blank = new SluiceProperties(base.admin(), new SluiceProperties.Security("", 1, 1, false),
                base.rateLimit(), base.config(), base.gateway(), base.cache());
        assertThatThrownBy(() -> new ApiKeyService(blank)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantDirectoryResolvesActiveKeysOnly() {
        RateLimitPolicy policy = new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW, 10, 60, 0);
        ConfigDocument document = new ConfigDocument(List.of(), List.of(), List.of(new PlanConfig("free", policy)),
                List.of(new TenantConfig("acme", "Acme", "free", false, List.of(
                                new ApiKeyRecord("k1", "hash-active", "sk_", Instant.EPOCH, false),
                                new ApiKeyRecord("k2", "hash-revoked", "sk_", Instant.EPOCH, true))),
                        new TenantConfig("gone", "Gone", "free", true,
                                List.of(new ApiKeyRecord("k3", "hash-disabled", "sk_", Instant.EPOCH, false))),
                        new TenantConfig("noplan", "No Plan", null, false,
                                List.of(new ApiKeyRecord("k4", "hash-noplan", "sk_", Instant.EPOCH, false)))),
                List.of(), null);
        TenantDirectory directory = TenantDirectory.from(document);
        assertThat(directory.authenticate("hash-active")).get().extracting(TenantInfo::id).isEqualTo("acme");
        assertThat(directory.authenticate("hash-active").orElseThrow().planPolicy()).isEqualTo(policy);
        assertThat(directory.authenticate("hash-revoked")).isEmpty();
        assertThat(directory.authenticate("hash-disabled")).isEmpty();
        assertThat(directory.authenticate("nope")).isEmpty();
        assertThat(directory.authenticate("hash-noplan").orElseThrow().planPolicy()).isNull();
        assertThat(directory.tenant("acme")).isPresent();
        assertThat(directory.tenant("gone")).isEmpty();
        assertThat(directory.tenantCount()).isEqualTo(2);
        assertThat(TenantDirectory.empty().tenantCount()).isZero();
    }

    private static String hsToken(String secret, Date expiry, String issuer, String audience, String tenant)
            throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("user-1").expirationTime(expiry);
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (audience != null) {
            claims.audience(audience);
        }
        if (tenant != null) {
            claims.claim("tenant", tenant);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes()));
        return jwt.serialize();
    }

    private static JwtVerifier hsVerifier(MutableClock clock, String issuer, String audience) throws Exception {
        JwtConfig config = new JwtConfig(JwtAlgorithm.HS256, "JWT_SECRET", null, issuer, audience, null);
        return JwtVerifier.create(config, clock, name -> name.equals("JWT_SECRET") ? SECRET : null);
    }

    @Test
    void hs256TokenIsVerifiedAndTenantClaimExtracted() throws Exception {
        MutableClock clock = new MutableClock();
        JwtVerifier verifier = hsVerifier(clock, "issuer", "aud");
        Date future = new Date(clock.millis() + 60_000);
        JwtVerifier.Claims claims = verifier.verify(hsToken(SECRET, future, "issuer", "aud", "acme")).orElseThrow();
        assertThat(claims.subject()).isEqualTo("user-1");
        assertThat(claims.tenant()).isEqualTo("acme");
        assertThat(verifier.verify(hsToken(SECRET, future, "issuer", "aud", null)).orElseThrow().tenant())
                .isEqualTo("user-1");
    }

    @Test
    void hs256RejectsBadSignatureExpiryIssuerAudienceAndGarbage() throws Exception {
        MutableClock clock = new MutableClock();
        JwtVerifier verifier = hsVerifier(clock, "issuer", "aud");
        Date future = new Date(clock.millis() + 60_000);
        assertThat(verifier.verify(hsToken("another-secret-another-secret-another!", future, "issuer", "aud", null)))
                .isEmpty();
        assertThat(verifier.verify(hsToken(SECRET, new Date(clock.millis() - 120_000), "issuer", "aud", null))).isEmpty();
        assertThat(verifier.verify(hsToken(SECRET, future, "evil", "aud", null))).isEmpty();
        assertThat(verifier.verify(hsToken(SECRET, future, "issuer", "other", null))).isEmpty();
        assertThat(verifier.verify(hsToken(SECRET, future, "issuer", null, null))).isEmpty();
        assertThat(verifier.verify("not-a-jwt")).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        JWTClaimsSet noExpiry = new JWTClaimsSet.Builder().subject("x").issuer("issuer").audience("aud").build();
        SignedJWT unsigned = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), noExpiry);
        unsigned.sign(new MACSigner(SECRET.getBytes()));
        assertThat(verifier.verify(unsigned.serialize())).isEmpty();
    }

    @Test
    void notBeforeInTheFutureIsRejectedButSmallSkewIsTolerated() throws Exception {
        MutableClock clock = new MutableClock();
        JwtVerifier verifier = hsVerifier(clock, null, null);
        Date expiry = new Date(clock.millis() + 600_000);
        SignedJWT early = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("a")
                .expirationTime(expiry).notBeforeTime(new Date(clock.millis() + 300_000)).build());
        early.sign(new MACSigner(SECRET.getBytes()));
        assertThat(verifier.verify(early.serialize())).isEmpty();
        SignedJWT slight = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("a")
                .expirationTime(expiry).notBeforeTime(new Date(clock.millis() + 10_000)).build());
        slight.sign(new MACSigner(SECRET.getBytes()));
        assertThat(verifier.verify(slight.serialize())).isPresent();
    }

    @Test
    void hs256RejectsTokensSignedWithAnotherAlgorithm() throws Exception {
        MutableClock clock = new MutableClock();
        JwtVerifier verifier = hsVerifier(clock, null, null);
        SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder().subject("a")
                .expirationTime(new Date(clock.millis() + 60_000)).build());
        hs512.sign(new MACSigner((SECRET + SECRET).getBytes()));
        assertThat(verifier.verify(hs512.serialize())).isEmpty();
    }

    @Test
    void shortOrMissingHmacSecretIsRefused() {
        JwtConfig config = new JwtConfig(JwtAlgorithm.HS256, "JWT_SECRET", null, null, null, null);
        assertThatThrownBy(() -> JwtVerifier.create(config, new MutableClock(), name -> "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JwtVerifier.create(config, new MutableClock(), name -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rs256TokenVerifiesAgainstStaticJwksFile(@TempDir Path directory) throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("key-2").generate();
        Path jwks = directory.resolve("jwks.json");
        Files.writeString(jwks, new JWKSet(signingKey.toPublicJWK()).toString());
        MutableClock clock = new MutableClock();
        JwtConfig config = new JwtConfig(JwtAlgorithm.RS256, null, jwks.toString(), null, null, null);
        JwtVerifier verifier = JwtVerifier.create(config, clock, name -> null);
        Date expiry = new Date(clock.millis() + 60_000);

        assertThat(rsToken(signingKey, "key-1", expiry)).satisfies(token ->
                assertThat(verifier.verify(token)).isPresent());
        assertThat(verifier.verify(rsToken(otherKey, "key-1", expiry))).isEmpty();
        assertThat(verifier.verify(rsToken(otherKey, "key-2", expiry))).isEmpty();
        assertThat(verifier.verify(rsToken(signingKey, null, expiry))).isPresent();
        assertThat(verifier.verify(hsToken(SECRET, expiry, null, null, null))).isEmpty();
    }

    @Test
    void missingJwksFileFailsFast(@TempDir Path directory) {
        JwtConfig config = new JwtConfig(JwtAlgorithm.RS256, null, directory.resolve("absent.json").toString(), null,
                null, null);
        assertThatThrownBy(() -> JwtVerifier.create(config, new MutableClock(), name -> null))
                .isInstanceOf(IOException.class);
        assertThat(Map.of()).isEmpty();
    }

    private static String rsToken(RSAKey key, String keyId, Date expiry) throws JOSEException, ParseException {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256);
        if (keyId != null) {
            header.keyID(keyId);
        }
        SignedJWT jwt = new SignedJWT(header.build(), new JWTClaimsSet.Builder().subject("rs-user").expirationTime(expiry)
                .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
