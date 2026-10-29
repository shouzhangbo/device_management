package com.example.device.ingest.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.device.core.config.DeviceIngestProperties;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 有界重试的三条边界：次数上限、预算耗尽、jitter 落在 {@code 1±jitter} 区间。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class RetryBudgetTest {

    private final DeviceIngestProperties properties = new DeviceIngestProperties();

    @Test
    void backoffStaysWithinJitterBand() {
        properties.setBackoffMs(100L);
        properties.setBackoffJitter(0.2D);
        RetryBudget budget = new RetryBudget(properties);
        long deadline = System.currentTimeMillis() + 60_000L;
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            long delay = budget.nextBackoffMillis(1, deadline);
            assertTrue(delay >= 80L && delay <= 120L, "delay out of jitter band: " + delay);
            seen.add(delay);
        }

        assertTrue(seen.size() > 10, "jitter must vary the delay, saw " + seen.size() + " distinct values");
    }

    @Test
    void backoffGrowsExponentially() {
        properties.setBackoffMs(10L);
        properties.setBackoffJitter(0.2D);
        RetryBudget budget = new RetryBudget(properties);
        long deadline = System.currentTimeMillis() + 60_000L;

        long first = budget.nextBackoffMillis(1, deadline);
        long fourth = budget.nextBackoffMillis(4, deadline);

        assertTrue(fourth > first * 4, "backoff must grow: first=" + first + " fourth=" + fourth);
    }

    @Test
    void backoffStopsWhenBudgetIsSpent() {
        RetryBudget budget = new RetryBudget(properties);

        assertEquals(-1L, budget.nextBackoffMillis(1, System.currentTimeMillis() - 1L));
    }

    @Test
    void runReturnsFirstSuccessWithoutNotifyingFailure() {
        RetryBudget budget = new RetryBudget(properties);
        AtomicInteger failures = new AtomicInteger();

        String result = budget.run(() -> "ok", failures::incrementAndGet);

        assertEquals("ok", result);
        assertEquals(0, failures.get());
    }

    @Test
    void runRetriesUntilSuccessAndNotifiesEachFailure() {
        properties.setMaxRetry(2);
        properties.setBackoffMs(1L);
        properties.setBackoffJitter(0D);
        RetryBudget budget = new RetryBudget(properties);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        String result = budget.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("conflict");
            }
            return "ok";
        }, failures::incrementAndGet);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(2, failures.get());
    }

    @Test
    void runExhaustsAttemptsAndKeepsLastCause() {
        properties.setMaxRetry(2);
        properties.setBackoffMs(1L);
        properties.setBackoffJitter(0D);
        RetryBudget budget = new RetryBudget(properties);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        RetryBudget.ExhaustedException thrown = assertThrows(RetryBudget.ExhaustedException.class,
                () -> budget.run(() -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("still conflicting");
                }, failures::incrementAndGet));

        assertEquals(3, calls.get());
        assertEquals(3, failures.get());
        assertEquals("retry exhausted after 3 attempts", thrown.getMessage());
        assertEquals("still conflicting", thrown.getCause().getMessage());
    }

    @Test
    void zeroBudgetStopsAfterFirstFailure() {
        properties.setRetryBudgetMs(0L);
        RetryBudget budget = new RetryBudget(properties);
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("conflict");

        RetryBudget.ExhaustedException thrown = assertThrows(RetryBudget.ExhaustedException.class,
                () -> budget.run(() -> {
                    calls.incrementAndGet();
                    throw boom;
                }, null));

        assertEquals(1, calls.get());
        assertSame(boom, thrown.getCause());
    }

    @Test
    void interruptionIsNotRetriedAndKeepsFlag() {
        RetryBudget budget = new RetryBudget(properties);
        InterruptedException interrupted = new InterruptedException("stop");

        RetryBudget.ExhaustedException thrown = assertThrows(RetryBudget.ExhaustedException.class,
                () -> budget.run(() -> {
                    throw interrupted;
                }, null));

        assertSame(interrupted, thrown.getCause());
        assertTrue(Thread.interrupted(), "interrupt flag must be restored");
    }
}
