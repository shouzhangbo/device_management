package com.example.device.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 链路 ID 的清洗与生成规则。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class TraceContextTest {

    @Test
    void generatedTraceIdIsThirtyTwoUppercaseHexChars() {
        String traceId = TraceContext.generate();
        assertTrue(traceId.matches("[0-9A-F]{32}"), traceId);
    }

    @Test
    void sanitizeKeepsAllowedCharset() {
        assertEquals("abc-123_X", TraceContext.sanitize(" abc-123_X "));
    }

    @Test
    void sanitizeTruncatesToMaxLength() {
        String result = TraceContext.sanitize("a".repeat(TraceContext.MAX_LENGTH + 5));
        assertEquals(TraceContext.MAX_LENGTH, result.length());
    }

    @Test
    void sanitizeRejectsControlCharactersAndSpaces() {
        assertNull(TraceContext.sanitize("abc\n123"));
        assertNull(TraceContext.sanitize("abc def"));
        assertNull(TraceContext.sanitize("  "));
        assertNull(TraceContext.sanitize(null));
    }

    @Test
    void currentIsEmptyOutsideRequestThread() {
        assertEquals("", TraceContext.current());
    }
}
