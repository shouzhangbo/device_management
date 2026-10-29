package com.example.device.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 链路 ID 过滤器：沿用/生成、响应头落定、MDC 清理。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void reusesInboundTraceIdAndExposesItInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/devices/SN-1");
        request.addHeader(TraceContext.HEADER, "trace-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] mdcSeen = new boolean[1];

        filter.doFilter(request, response, (req, res) -> {
            mdcSeen[0] = "trace-abc-123".equals(TraceContext.current());
        });

        assertTrue(mdcSeen[0], "MDC must carry the trace id while the chain runs");
        assertEquals("trace-abc-123", response.getHeader(TraceContext.HEADER));
        assertEquals("", TraceContext.current(), "MDC must be cleared after the request");
    }

    @Test
    void generatesTraceIdWhenInboundIsInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/devices/SN-1");
        request.addHeader(TraceContext.HEADER, "bad\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        String header = response.getHeader(TraceContext.HEADER);
        assertTrue(header != null && header.matches("[0-9A-F]{32}"), header);
    }

    @Test
    void clearsMdcEvenWhenChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/devices/SN-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception ignored) {
            // 只关心 finally 是否清理
        }

        assertFalse(TraceContext.current().contains("boom"));
        assertEquals("", TraceContext.current());
    }
}
