package com.example.device.openapi.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 固定窗口配额：不设上限拒绝启动、窗口内计数、超限拒绝、Redis 故障 fail-open。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class QuotaRateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    @Test
    void refusesToStartWithoutPositiveQuota() {
        assertThrows(IllegalStateException.class, () -> new QuotaRateLimiter(redisTemplate, 0L));
        assertThrows(IllegalStateException.class, () -> new QuotaRateLimiter(redisTemplate, -1L));
    }

    @Test
    void firstHitSetsWindowTtlAndLeavesRemaining() {
        when(ops.increment(anyString())).thenReturn(1L);
        QuotaRateLimiter limiter = new QuotaRateLimiter(redisTemplate, 5L);

        QuotaRateLimiter.Decision decision = limiter.tryAcquire("app-a");

        assertTrue(decision.allowed());
        assertEquals(5L, decision.limit());
        assertEquals(4L, decision.remaining());
        verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(2)));
    }

    @Test
    void laterHitsDoNotResetTtl() {
        when(ops.increment(anyString())).thenReturn(2L);
        QuotaRateLimiter limiter = new QuotaRateLimiter(redisTemplate, 5L);

        limiter.tryAcquire("app-a");

        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ofSeconds(2)));
    }

    @Test
    void countAtLimitIsStillAllowed() {
        when(ops.increment(anyString())).thenReturn(5L);

        QuotaRateLimiter.Decision decision = new QuotaRateLimiter(redisTemplate, 5L).tryAcquire("app-a");

        assertTrue(decision.allowed());
        assertEquals(0L, decision.remaining());
    }

    @Test
    void countBeyondLimitIsRejectedWithoutNegativeRemaining() {
        when(ops.increment(anyString())).thenReturn(6L);

        QuotaRateLimiter.Decision decision = new QuotaRateLimiter(redisTemplate, 5L).tryAcquire("app-a");

        assertFalse(decision.allowed());
        assertEquals(0L, decision.remaining());
    }

    @Test
    void nullCounterTreatedAsFirstHit() {
        when(ops.increment(anyString())).thenReturn(null);

        QuotaRateLimiter.Decision decision = new QuotaRateLimiter(redisTemplate, 5L).tryAcquire("app-a");

        assertTrue(decision.allowed());
        assertEquals(1L, decision.limit() - decision.remaining());
    }

    @Test
    void redisFailureFailsOpenWithFullQuota() {
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        QuotaRateLimiter.Decision decision = new QuotaRateLimiter(redisTemplate, 5L).tryAcquire("app-a");

        assertTrue(decision.allowed());
        assertEquals(5L, decision.remaining());
    }
}
