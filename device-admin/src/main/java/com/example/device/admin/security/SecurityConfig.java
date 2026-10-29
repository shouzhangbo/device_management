package com.example.device.admin.security;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.web.IdempotencyKeyFilter;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.ErrorCode;
import com.example.device.core.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 后台安全配置：JWT 资源服务器 + 统一错误外壳 + 幂等键过滤器。
 * <p>
 * 三条硬约束：① 只接受 RS256，且算法由公钥解码器<b>固定</b>，不读取 token 头 {@code alg}
 * （禁止算法混淆 / {@code none}）；② {@code iss}/{@code aud}/{@code exp} 全部校验；
 * ③ 公钥只从挂载文件读取，缺失即拒绝启动，禁止写进 yaml 或代码常量。
 * </p>
 * <p>
 * 未认证 → {@code 401/4011}（后台场景与 4010 同义，契约 {@code AdminJwt}）；已认证但被拒绝
 * （含访问非后台路径）→ {@code 403/4030}，这条也是 4030 的可达触发路径。
 * 过滤器顺序：链路 ID 最先（401/403 响应也要带 {@code trace_id}），幂等键过滤器在安全链之后
 * （未认证请求先得到 401，而不是先被报缺头）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String ADMIN_PATH = "/api/v1/admin/**";

    private static final int IDEMPOTENCY_FILTER_ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 10;

    /**
     * 安全过滤链。
     *
     * @param http      HttpSecurity 构建器
     * @param jwtDecoder 已配置好校验器的解码器
     * @param objectMapper 统一外壳的序列化器
     * @return 过滤链
     * @throws Exception 构建失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                   ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) ->
                writeError(objectMapper, response, ErrorCode.TIMESTAMP_OUT_OF_WINDOW);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeError(objectMapper, response, ErrorCode.FORBIDDEN);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers(ADMIN_PATH).authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * JWT 解码器：RS256 + issuer + audience + 有效期的组合校验。
     *
     * @param properties {@code device.admin.jwt.*}
     * @return 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(AdminProperties properties) {
        AdminProperties.Jwt jwt = properties.jwt();
        if (jwt == null || isBlank(jwt.issuer()) || isBlank(jwt.audience()) || isBlank(jwt.publicKeyPath())) {
            throw new IllegalStateException(
                    "device.admin.jwt.issuer / audience / public-key-path must all be configured");
        }
        RSAPublicKey publicKey = loadPublicKey(jwt.publicKeyPath());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.issuer()),
                audienceValidator(jwt.audience()));
        decoder.setJwtValidator(validators);
        LOG.info("admin jwt decoder ready, issuer={}, audience={}", jwt.issuer(), jwt.audience());
        return decoder;
    }

    /**
     * 链路 ID 过滤器：最先执行，使 401/403 外壳里的 {@code trace_id} 与响应头同值。
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 建档幂等键过滤器：只作用于 {@code POST /api/v1/admin/devices}。
     *
     * @param objectMapper 外壳序列化器
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<IdempotencyKeyFilter> idempotencyKeyFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<IdempotencyKeyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyKeyFilter(objectMapper));
        registration.addUrlPatterns("/api/v1/admin/devices");
        registration.setOrder(IDEMPOTENCY_FILTER_ORDER);
        return registration;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "required audience is missing", null));
    }

    private static RSAPublicKey loadPublicKey(String path) {
        Path keyPath = Path.of(path);
        if (!Files.isReadable(keyPath)) {
            throw new IllegalStateException("jwt public key file is not readable: " + path);
        }
        try {
            String pem = Files.readString(keyPath, StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("jwt public key is not a valid X.509 PEM file: " + path, e);
        }
    }

    private static void writeError(ObjectMapper objectMapper, HttpServletResponse response, ErrorCode code)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiEnvelope.error(code));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
