package com.example.device.ingest.cron;

import java.time.Duration;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * 由 cron 表达式推算调度周期：锁 TTL 与"单轮截断"都以周期为基准，而不是另造一个周期配置键
 * （{@code tech-design} §5.2：{@code TTL = 周期 × device.cron.lock-ttl-ratio}）。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class CronSchedule {

    private CronSchedule() {
    }

    /**
     * 周期 = 连续两次触发的时间差。
     *
     * @param cron     调度表达式，允许为 {@code null}
     * @param fallback 表达式缺失或不可解析时的兜底周期
     * @return 周期，永不为 {@code null}
     */
    public static Duration period(String cron, Duration fallback) {
        if (cron == null || cron.isBlank()) {
            return fallback;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime first = expression.next(now);
            ZonedDateTime second = first == null ? null : expression.next(first);
            if (second == null) {
                return fallback;
            }
            Duration period = Duration.between(first, second);
            return period.isZero() || period.isNegative() ? fallback : period;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 锁 TTL = 周期 × 比例。
     *
     * @param period 周期
     * @param ratio  比例，来自 {@code device.cron.lock-ttl-ratio}
     * @return 锁存活时长
     */
    public static Duration lockTtl(Duration period, double ratio) {
        long millis = (long) (period.toMillis() * ratio);
        return Duration.ofMillis(Math.max(1L, millis));
    }
}
