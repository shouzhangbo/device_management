package com.example.device.openapi.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.example.device.openapi.ratelimit.QuotaRateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验签与限流的固定顺序：尺寸 → 三头 → 时间窗 → 签名 → AppKey → 限流；任何一步失败都不得落到下一步。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class SignatureAuthInterceptorTest {

    private static final String APP_KEY = "app-a";

    private static final String SECRET = "cur-secret";

    private static final String PREVIOUS = "prev-secret";

    private static final String PATH = "/api/v1/devices/SN-1";

    private final AppKeyRegistry registry = mock(AppKeyRegistry.class);

    private final QuotaRateLimiter rateLimiter = mock(QuotaRateLimiter.class);

    private final SignatureAuthInterceptor interceptor =
            new SignatureAuthInterceptor(registry, rateLimiter, 300L);

    @Test
    void validSignaturePassesAndWritesRateLimitHeaders() {
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET));
        when(rateLimiter.tryAcquire(APP_KEY)).thenReturn(allowed(10L, 9L));
        MockHttpServletRequest request = signedRequest(new byte[0], SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals("10", response.getHeader(SignatureAuthInterceptor.HEADER_RATE_LIMIT));
        assertEquals("9", response.getHeader(SignatureAuthInterceptor.HEADER_RATE_REMAINING));
        assertEquals("no-store", response.getHeader(SignatureAuthInterceptor.HEADER_CACHE_CONTROL));
    }

    @Test
    void signatureFromPreviousSecretStillPassesDuringRotation() {
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET, PREVIOUS));
        when(rateLimiter.tryAcquire(APP_KEY)).thenReturn(allowed(10L, 9L));
        MockHttpServletRequest request = signedRequest(new byte[0], PREVIOUS);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void uppercaseSignatureIsAccepted() {
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET));
        when(rateLimiter.tryAcquire(APP_KEY)).thenReturn(allowed(10L, 9L));
        MockHttpServletRequest request = signedRequest(new byte[0], SECRET);
        request.removeHeader(SignatureAuthInterceptor.HEADER_SIGNATURE);
        long ts = Long.parseLong(request.getHeader(SignatureAuthInterceptor.HEADER_TIMESTAMP));
        request.addHeader(SignatureAuthInterceptor.HEADER_SIGNATURE,
                hmac(SECRET, payload("GET", PATH, ts, new byte[0])).toUpperCase(Locale.ROOT));

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void bodyDigestIsPartOfSignature() {
        byte[] body = "{\"sns\":[\"SN-1\"]}".getBytes(StandardCharsets.UTF_8);
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET));
        MockHttpServletRequest request = signedRequest(body, SECRET);
        request.removeHeader(SignatureAuthInterceptor.HEADER_SIGNATURE);
        long ts = Long.parseLong(request.getHeader(SignatureAuthInterceptor.HEADER_TIMESTAMP));
        // 用空 body 的摘要签名，body 非空时必须失败。
        request.addHeader(SignatureAuthInterceptor.HEADER_SIGNATURE,
                hmac(SECRET, payload("GET", PATH, ts, new byte[0])));

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.SIGN_INVALID, thrown.errorCode());
    }

    @Test
    void missingHeadersAreSignatureFailures() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.SIGN_INVALID, thrown.errorCode());
    }

    @Test
    void nonNumericTimestampIsRejectedBeforeSignatureCheck() {
        MockHttpServletRequest request = signedRequest(new byte[0], SECRET);
        request.removeHeader(SignatureAuthInterceptor.HEADER_TIMESTAMP);
        request.addHeader(SignatureAuthInterceptor.HEADER_TIMESTAMP, "not-a-number");

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, thrown.errorCode());
    }

    @Test
    void staleTimestampIsRejected() {
        long stale = System.currentTimeMillis() - 301_000L;
        MockHttpServletRequest request = requestWith("GET", PATH, new byte[0], stale, "whatever");

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, thrown.errorCode());
    }

    @Test
    void unknownAppKeyIsIndistinguishableFromBadSignature() {
        when(registry.secretsOf("ghost")).thenReturn(List.of());
        MockHttpServletRequest request = signedRequest(new byte[0], SECRET);
        request.removeHeader(SignatureAuthInterceptor.HEADER_APP_KEY);
        request.addHeader(SignatureAuthInterceptor.HEADER_APP_KEY, "ghost");

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.SIGN_INVALID, thrown.errorCode());
    }

    @Test
    void wrongSignatureIsRejected() {
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET));
        MockHttpServletRequest request = signedRequest(new byte[0], "other-secret");

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.SIGN_INVALID, thrown.errorCode());
    }

    @Test
    void rateLimitedRequestGetsRetryAfter() {
        when(registry.secretsOf(APP_KEY)).thenReturn(List.of(SECRET));
        when(rateLimiter.tryAcquire(APP_KEY))
                .thenReturn(new QuotaRateLimiter.Decision(false, 10L, 0L, System.currentTimeMillis() / 1000L + 1L));
        MockHttpServletRequest request = signedRequest(new byte[0], SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(ErrorCode.RATE_LIMITED, thrown.errorCode());
        assertTrue(Long.parseLong(response.getHeader(SignatureAuthInterceptor.HEADER_RETRY_AFTER)) >= 1L);
    }

    @Test
    void oversizedBodyIsRejectedBeforeHeaderChecks() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setAttribute(SignatureAuthInterceptor.CachedBodyHolder.TOO_LARGE_ATTRIBUTE, Boolean.TRUE);

        BizException thrown = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
    }

    private MockHttpServletRequest signedRequest(byte[] body, String secret) {
        long ts = System.currentTimeMillis();
        String signature = hmac(secret, payload("GET", PATH, ts, body));
        MockHttpServletRequest request = requestWith("GET", PATH, body, ts, signature);
        request.setAttribute(SignatureAuthInterceptor.CachedBodyHolder.BODY_ATTRIBUTE, body.clone());
        return request;
    }

    private static MockHttpServletRequest requestWith(String method, String path, byte[] body, long ts,
                                                     String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);
        request.addHeader(SignatureAuthInterceptor.HEADER_APP_KEY, APP_KEY);
        request.addHeader(SignatureAuthInterceptor.HEADER_TIMESTAMP, Long.toString(ts));
        request.addHeader(SignatureAuthInterceptor.HEADER_SIGNATURE, signature);
        return request;
    }

    private static QuotaRateLimiter.Decision allowed(long limit, long remaining) {
        return new QuotaRateLimiter.Decision(true, limit, remaining, System.currentTimeMillis() / 1000L + 1L);
    }

    private static String payload(String method, String path, long ts, byte[] body) {
        return method + "\n" + path + "\n" + ts + "\n" + sha256Hex(body);
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
