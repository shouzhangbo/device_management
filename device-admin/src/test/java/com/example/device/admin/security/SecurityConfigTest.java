package com.example.device.admin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.device.admin.config.AdminProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT 解码器的四条硬约束：RS256 固定、issuer/audience/exp 校验、公钥只从文件读、缺配置拒绝启动。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class SecurityConfigTest {

    private static final String ISSUER = "issuer-x";

    private static final String AUDIENCE = "aud-x";

    private static KeyPair keyPair;

    private static JwtEncoder encoder;

    @TempDir
    static Path tempDir;

    private final SecurityConfig securityConfig = new SecurityConfig();

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
    }

    @Test
    void validTokenIsDecodedWithConfiguredIssuerAndAudience() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());

        Jwt jwt = decoder.decode(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
                SignatureAlgorithm.RS256));

        assertEquals("zhangsan", jwt.getSubject());
        assertEquals(AUDIENCE, jwt.getAudience().get(0));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());

        assertThrows(JwtException.class, () -> decoder.decode(
                token(ISSUER, "other-aud", Instant.now().plusSeconds(300), SignatureAlgorithm.RS256)));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());

        assertThrows(JwtException.class, () -> decoder.decode(
                token("other-issuer", AUDIENCE, Instant.now().plusSeconds(300), SignatureAlgorithm.RS256)));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());

        assertThrows(JwtException.class, () -> decoder.decode(
                token(ISSUER, AUDIENCE, Instant.now().minusSeconds(600), SignatureAlgorithm.RS256)));
    }

    @Test
    void tokenSignedWithAnotherAlgorithmIsRejected() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());

        assertThrows(JwtException.class, () -> decoder.decode(
                token(ISSUER, AUDIENCE, Instant.now().plusSeconds(300), SignatureAlgorithm.RS384)));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() throws Exception {
        JwtDecoder decoder = decoderWith(pemFile());
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + ISSUER + "\",\"aud\":[\"" + AUDIENCE
                + "\"],\"sub\":\"zhangsan\",\"exp\":" + (Instant.now().getEpochSecond() + 300) + "}");

        assertThrows(JwtException.class, () -> decoder.decode(header + "." + payload + "."));
    }

    @Test
    void incompleteConfigurationFailsStartup() throws Exception {
        String pem = pemFile();
        assertThrows(IllegalStateException.class,
                () -> securityConfig.jwtDecoder(new AdminProperties(10_000, null)));
        assertThrows(IllegalStateException.class,
                () -> securityConfig.jwtDecoder(new AdminProperties(10_000, new AdminProperties.Jwt(" ", AUDIENCE, pem))));
        assertThrows(IllegalStateException.class,
                () -> securityConfig.jwtDecoder(new AdminProperties(10_000, new AdminProperties.Jwt(ISSUER, "", pem))));
        assertThrows(IllegalStateException.class,
                () -> securityConfig.jwtDecoder(new AdminProperties(10_000, new AdminProperties.Jwt(ISSUER, AUDIENCE, null))));
    }

    @Test
    void unreadableKeyFileFailsStartup() {
        String missing = tempDir.resolve("missing.pem").toString();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> securityConfig.jwtDecoder(new AdminProperties(10_000,
                        new AdminProperties.Jwt(ISSUER, AUDIENCE, missing))));

        assertTrue(thrown.getMessage().contains("not readable"));
    }

    private JwtDecoder decoderWith(String pemPath) {
        return securityConfig.jwtDecoder(new AdminProperties(10_000,
                new AdminProperties.Jwt(ISSUER, AUDIENCE, pemPath)));
    }

    private static String token(String issuer, String audience, Instant expiresAt, SignatureAlgorithm algorithm) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("zhangsan")
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(algorithm).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String pemFile() throws Exception {
        Path file = tempDir.resolve("jwt-public.pem");
        if (!Files.exists(file)) {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(keyPair.getPublic().getEncoded());
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n",
                    StandardCharsets.UTF_8);
        }
        return file.toString();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
