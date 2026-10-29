package com.example.device.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 报文长度与 SN 字符集的硬边界。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class PayloadLimitsTest {

    @Test
    void snPatternAcceptsContractCharset() {
        assertTrue("ABC-123_x".matches(PayloadLimits.SN_PATTERN));
        assertTrue("PB2026102900001".matches(PayloadLimits.SN_PATTERN));
    }

    @Test
    void snPatternRejectsSpacesAndNonAscii() {
        assertFalse("ABC 123".matches(PayloadLimits.SN_PATTERN));
        assertFalse("设备-1".matches(PayloadLimits.SN_PATTERN));
        assertFalse("".matches(PayloadLimits.SN_PATTERN));
    }

    @Test
    void failReasonIsTruncatedToColumnWidth() {
        String longText = "x".repeat(PayloadLimits.MAX_FAIL_REASON_LENGTH + 10);
        assertEquals(PayloadLimits.MAX_FAIL_REASON_LENGTH, PayloadLimits.truncateFailReason(longText).length());
    }

    @Test
    void blankFailReasonBecomesEmptyString() {
        assertEquals("", PayloadLimits.truncateFailReason(null));
        assertEquals("", PayloadLimits.truncateFailReason("   "));
        assertEquals("boom", PayloadLimits.truncateFailReason("boom"));
    }
}
