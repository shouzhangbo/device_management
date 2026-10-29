package com.example.device.openapi.security;

import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.example.device.openapi.ratelimit.QuotaRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对外接口的 AppKey HMAC 验签与配额限流（EP-001 / EP-002）。
 * <p>
 * 校验顺序固定为：请求体尺寸 → 三头齐全 → 时间窗 → 验签 → AppKey 有效性 → 限流
 * （{@code implementation-map} IM-OPENAPI-001 控制流）。<b>限流必须在验签之后</b>：
 * 未通过验签的流量不消耗配额，否则攻击者可用垃圾请求打满某个 AppKey 的计数。
 * </p>
 * <p>
 * 签名串与契约 {@code securitySchemes.AppKeyHmac} 逐字符一致：
 * {@code HMAC-SHA256(secret, method + "\n" + path + "\n" + timestamp + "\n" + bodySha256)}，
 * 比较用 {@link MessageDigest#isEqual} 常数时间比较，禁止 {@code String.equals}。
 * 未知 AppKey 与错签名都返回 {@code 401/4010}（契约 {@code Unauthorized} 描述行：
 * 不向调用方区分"键不存在"与"签名错"，避免枚举 AppKey）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class SignatureAuthInterceptor implements HandlerInterceptor {

    /** 调用方标识头。 */
    public static final String HEADER_APP_KEY = "X-App-Key";

    /** 客户端毫秒时间戳头。 */
    public static final String HEADER_TIMESTAMP = "X-Timestamp";

    /** HMAC 十六进制签名头。 */
    public static final String HEADER_SIGNATURE = "X-Signature";

    /** 限流响应头：窗口内上限。 */
    public static final String HEADER_RATE_LIMIT = "X-RateLimit-Limit";

    /** 限流响应头：剩余次数。 */
    public static final String HEADER_RATE_REMAINING = "X-RateLimit-Remaining";

    /** 限流响应头：窗口重置时间（Unix 秒）。 */
    public static final String HEADER_RATE_RESET = "X-RateLimit-Reset";

    /** 429 必带的建议重试秒数。 */
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    /** 禁止 HTTP/CDN 缓存：本系统已是最终一致，再叠一层缓存会让不一致窗口不可观测。 */
    public static final String HEADER_CACHE_CONTROL = "Cache-Control";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Logger LOG = LoggerFactory.getLogger(SignatureAuthInterceptor.class);

    private final AppKeyRegistry appKeyRegistry;
    private final QuotaRateLimiter rateLimiter;
    private final long signWindowMillis;

    /**
     * @param appKeyRegistry     secret 注册表
     * @param rateLimiter        按 AppKey 的配额限流
     * @param signWindowSeconds  {@code device.openapi.sign-window-seconds}，缺省 300（与契约 ±5min 一致）
     */
    public SignatureAuthInterceptor(AppKeyRegistry appKeyRegistry, QuotaRateLimiter rateLimiter,
                                    @Value("${device.openapi.sign-window-seconds:300}") long signWindowSeconds) {
        this.appKeyRegistry = appKeyRegistry;
        this.rateLimiter = rateLimiter;
        this.signWindowMillis = Math.max(1L, signWindowSeconds) * 1000L;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (Boolean.TRUE.equals(request.getAttribute(CachedBodyHolder.TOO_LARGE_ATTRIBUTE))) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "request body exceeds the verification limit");
        }
        String appKey = request.getHeader(HEADER_APP_KEY);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(appKey) || isBlank(timestamp) || isBlank(signature)) {
            throw new BizException(ErrorCode.SIGN_INVALID);
        }
        requireTimestampInWindow(timestamp.trim());

        List<String> secrets = appKeyRegistry.secretsOf(appKey.trim());
        if (secrets.isEmpty()) {
            LOG.warn("unknown app key rejected, path={}", request.getRequestURI());
            throw new BizException(ErrorCode.SIGN_INVALID);
        }
        String payload = request.getMethod() + "\n" + request.getRequestURI() + "\n" + timestamp.trim()
                + "\n" + bodySha256Hex(request);
        if (!matchesAnySecret(secrets, payload, signature.trim())) {
            LOG.warn("signature mismatch, appKey={}, path={}", appKey.trim(), request.getRequestURI());
            throw new BizException(ErrorCode.SIGN_INVALID);
        }

        QuotaRateLimiter.Decision decision = rateLimiter.tryAcquire(appKey.trim());
        writeRateLimitHeaders(response, decision);
        response.setHeader(HEADER_CACHE_CONTROL, "no-store");
        if (!decision.allowed()) {
            long retryAfter = Math.max(1L, decision.resetEpochSec() - System.currentTimeMillis() / 1000L);
            response.setHeader(HEADER_RETRY_AFTER, Long.toString(retryAfter));
            LOG.warn("quota exceeded, appKey={}", appKey.trim());
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    private void requireTimestampInWindow(String timestamp) {
        long value;
        try {
            value = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.TIMESTAMP_OUT_OF_WINDOW);
        }
        long now = System.currentTimeMillis();
        if (value < now - signWindowMillis || value > now + signWindowMillis) {
            throw new BizException(ErrorCode.TIMESTAMP_OUT_OF_WINDOW);
        }
    }

    private static boolean matchesAnySecret(List<String> secrets, String payload, String signature) {
        byte[] provided = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        for (String secret : secrets) {
            byte[] expected = hmacSha256Hex(secret, payload).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, provided)) {
                return true;
            }
        }
        return false;
    }

    private static void writeRateLimitHeaders(HttpServletResponse response, QuotaRateLimiter.Decision decision) {
        response.setHeader(HEADER_RATE_LIMIT, Long.toString(decision.limit()));
        response.setHeader(HEADER_RATE_REMAINING, Long.toString(decision.remaining()));
        response.setHeader(HEADER_RATE_RESET, Long.toString(decision.resetEpochSec()));
    }

    private static String bodySha256Hex(HttpServletRequest request) {
        Object cached = request.getAttribute(CachedBodyHolder.BODY_ATTRIBUTE);
        byte[] body = cached instanceof byte[] bytes ? bytes : new byte[0];
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 请求体缓存属性的常量持有者：过滤器写、拦截器读，两侧必须同串。
     * <p>
     * 单独成类是为了让 {@code SignatureAuthInterceptor} 不直接依赖过滤器实现类，
     * 避免"拦截器—过滤器"的循环引用。
     * </p>
     *
     * @author houzhangbo
     * @since 2026-10-29
     */
    public static final class CachedBodyHolder {

        /** 请求体字节数组属性名。 */
        public static final String BODY_ATTRIBUTE = "com.example.device.openapi.cachedBody";

        /** 请求体超限标记属性名。 */
        public static final String TOO_LARGE_ATTRIBUTE = "com.example.device.openapi.cachedBodyTooLarge";

        private CachedBodyHolder() {
        }
    }
}
