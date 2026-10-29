package com.example.device.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.device.core.cache.CacheKeys;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code Idempotency-Key} 去重的四条契约：首次执行落快照、重放不执行动作、失败释放占位、Redis 故障放行。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class IdempotencyKeyGuardTest {

    private static final String KEY = "abcdefgh";

    private static final String REDIS_KEY = CacheKeys.adminIdem(KEY);

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IdempotencyKeyGuard guard;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        guard = new IdempotencyKeyGuard(redisTemplate, objectMapper, 0);
    }

    @Test
    void firstCallExecutesActionAndStoresSnapshotWithTtl() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), eq(TTL))).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        Snapshot result = guard.runIfNew(KEY, Snapshot.class, () -> {
            calls.incrementAndGet();
            return new Snapshot("SN-1");
        });

        assertEquals("SN-1", result.sn());
        assertEquals(1, calls.get());
        verify(ops).set(REDIS_KEY, "{\"sn\":\"SN-1\"}", TTL);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void nonPositiveTtlFallsBackToTwentyFourHours() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), eq(TTL))).thenReturn(true);
        IdempotencyKeyGuard zeroTtl = new IdempotencyKeyGuard(redisTemplate, objectMapper, 0);

        zeroTtl.runIfNew(KEY, Snapshot.class, () -> new Snapshot("SN-1"));

        verify(ops).set(REDIS_KEY, "{\"sn\":\"SN-1\"}", TTL);
    }

    @Test
    void replayReturnsStoredSnapshotWithoutRunningAction() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(REDIS_KEY)).thenReturn("{\"sn\":\"SN-OLD\"}");
        AtomicInteger calls = new AtomicInteger();

        Snapshot result = guard.runIfNew(KEY, Snapshot.class, () -> {
            calls.incrementAndGet();
            return new Snapshot("SN-NEW");
        });

        assertEquals("SN-OLD", result.sn());
        assertEquals(0, calls.get());
    }

    @Test
    void failedFirstCallReleasesPlaceholder() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class))).thenReturn(true);
        IllegalStateException boom = new IllegalStateException("db down");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard.runIfNew(KEY, Snapshot.class, () -> {
                    throw boom;
                }));

        assertSame(boom, thrown);
        verify(redisTemplate).delete(REDIS_KEY);
        verify(ops, never()).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureOnClaimFallsBackToExecutingAction() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        Snapshot result = guard.runIfNew(KEY, Snapshot.class, () -> new Snapshot("SN-1"));

        assertEquals("SN-1", result.sn());
    }

    @Test
    void inFlightPlaceholderTimesOutAsConflict() {
        when(ops.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(REDIS_KEY)).thenReturn("__IN_FLIGHT__");
        AtomicInteger calls = new AtomicInteger();

        BizException thrown = assertThrows(BizException.class,
                () -> guard.runIfNew(KEY, Snapshot.class, () -> {
                    calls.incrementAndGet();
                    return new Snapshot("SN-1");
                }));

        assertEquals(ErrorCode.SN_ALREADY_EXISTS, thrown.errorCode());
        assertEquals(0, calls.get());
    }

    /** 与响应同构的最小快照类型。 */
    record Snapshot(String sn) {
    }
}
