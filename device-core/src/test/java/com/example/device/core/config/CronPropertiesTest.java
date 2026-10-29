package com.example.device.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 定时任务参数的不变式与缺省回落。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class CronPropertiesTest {

    @Test
    void ratioMustBeStrictlyBetweenZeroAndOne() {
        assertFalse(CronProperties.validRatio(null));
        assertFalse(CronProperties.validRatio(0.0));
        assertFalse(CronProperties.validRatio(1.0));
        assertFalse(CronProperties.validRatio(1.5));
        assertTrue(CronProperties.validRatio(0.8));
        assertTrue(CronProperties.validRatio(0.2));
    }

    @Test
    void missingRatiosFallBackToDesignDefaults() {
        CronProperties props = new CronProperties(null, null, null);
        assertEquals(CronProperties.DEFAULT_LOCK_TTL_RATIO, props.lockTtlRatioOrDefault());
        assertEquals(CronProperties.DEFAULT_SAFETY_MARGIN_RATIO, props.safetyMarginRatioOrDefault());
    }

    @Test
    void watermarkDefaultsToOneHundredSixtyEightHours() {
        assertEquals(Duration.ofHours(168), new CronProperties(null, null, null).watermarkTtlOrDefault());
        assertEquals(Duration.ofHours(24), new CronProperties(null, null, 24).watermarkTtlOrDefault());
        assertEquals(Duration.ofHours(168), new CronProperties(null, null, 0).watermarkTtlOrDefault());
    }
}
