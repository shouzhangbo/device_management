package com.example.device.openapi.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.device.openapi.security.SignatureAuthInterceptor.CachedBodyHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 请求体缓存：两次读取互不影响、只对 {@code /api/v1/**} 生效、超限打标记且不缓存。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class CachedBodyRequestFilterTest {

    private final CachedBodyRequestFilter filter = new CachedBodyRequestFilter();

    @Test
    void signedRequestCanBeReadTwiceFromCache() throws Exception {
        byte[] body = "{\"sns\":[\"SN-1\"]}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/devices/batch");
        request.setContent(body);
        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertArrayEquals(body, (byte[]) captured.get().getAttribute(CachedBodyHolder.BODY_ATTRIBUTE));
        assertArrayEquals(body, captured.get().getInputStream().readAllBytes());
        assertArrayEquals(body, captured.get().getInputStream().readAllBytes());
        assertEquals(body.length, captured.get().getContentLength());
    }

    @Test
    void nonSignedPathIsPassedThroughUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setContent("ping".getBytes(StandardCharsets.UTF_8));
        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertSame(request, captured.get());
    }

    @Test
    void oversizedBodyIsFlaggedAndNotCached() throws Exception {
        byte[] body = new byte[CachedBodyRequestFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/devices/batch");
        request.setContent(body);
        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertEquals(Boolean.TRUE, captured.get().getAttribute(CachedBodyHolder.TOO_LARGE_ATTRIBUTE));
        assertNull(captured.get().getAttribute(CachedBodyHolder.BODY_ATTRIBUTE));
        assertEquals(0, captured.get().getInputStream().readAllBytes().length);
    }

    @Test
    void exactlyMaxSizeIsStillCached() throws Exception {
        byte[] body = new byte[CachedBodyRequestFilter.MAX_BODY_BYTES];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/devices/batch");
        request.setContent(body);
        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertTrue(captured.get().getAttribute(CachedBodyHolder.BODY_ATTRIBUTE) instanceof byte[]);
        assertArrayEquals(body, captured.get().getInputStream().readAllBytes());
    }
}
