package com.example.device.core.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device.cache.*} 的类型安全绑定（Spring Boot 规范【强制】不允许在热路径读 {@code Environment}）。
 * <p>
 * 每个键都带设计文档给的"建议值"作为默认，真实值由需求方确认后写进各进程的 {@code application.yaml}；
 * 缺键时进程仍可启动，但 {@code DevicePropertiesValidation} 会断言取到的值合法（TTL 与抖动必须为正）。
 * </p>
 *
 * @param ttlBase          缓存基础 TTL
 * @param ttlJitter        TTL 随机抖动上界，实际 TTL = base + random(0..jitter)
 * @param emptyTtl         空值哨兵 TTL（远短于正常 TTL，防止不存在的 SN 长期占位）
 * @param rebuildSpinTimes 未抢到重建锁时的自旋次数上限，禁止无界自旋
 * @param reconcile        EP-007 对账任务参数
 * @author houzhangbo
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "device.cache")
public record CacheProperties(Duration ttlBase, Duration ttlJitter, Duration emptyTtl,
                             Integer rebuildSpinTimes, Reconcile reconcile) {

    /** 默认 TTL：{@code tech-design} §9.4 建议值 1800s + 0~300s 抖动。 */
    public static final Duration DEFAULT_TTL_BASE = Duration.ofSeconds(1800);

    /** 默认抖动上界。 */
    public static final Duration DEFAULT_TTL_JITTER = Duration.ofSeconds(300);

    /** 默认哨兵 TTL。 */
    public static final Duration DEFAULT_EMPTY_TTL = Duration.ofSeconds(60);

    /** 默认自旋次数。 */
    public static final int DEFAULT_SPIN_TIMES = 3;

    /**
     * 基础 TTL，缺键时回落到设计建议值而不是 {@code null}。
     *
     * @return 生效的基础 TTL
     */
    public Duration ttlBaseOrDefault() {
        return ttlBase == null ? DEFAULT_TTL_BASE : ttlBase;
    }

    /**
     * 抖动上界。
     *
     * @return 生效的抖动上界
     */
    public Duration ttlJitterOrDefault() {
        return ttlJitter == null ? DEFAULT_TTL_JITTER : ttlJitter;
    }

    /**
     * 哨兵 TTL。
     *
     * @return 生效的空值 TTL
     */
    public Duration emptyTtlOrDefault() {
        return emptyTtl == null ? DEFAULT_EMPTY_TTL : emptyTtl;
    }

    /**
     * 自旋次数：只接受正数，非正一律回落默认，避免无界自旋或跳过等待。
     *
     * @return 生效的自旋次数
     */
    public int spinTimesOrDefault() {
        return rebuildSpinTimes == null || rebuildSpinTimes <= 0 ? DEFAULT_SPIN_TIMES : rebuildSpinTimes;
    }

    /**
     * EP-007 缓存对账参数。
     *
     * @param batchSize 单批处理行数上限
     * @param cron      调度表达式
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record Reconcile(Integer batchSize, String cron) {

        /** 默认单批行数。 */
        public static final int DEFAULT_BATCH_SIZE = 500;

        /**
         * 单批行数。
         *
         * @return 生效的批量大小
         */
        public int batchSizeOrDefault() {
            return batchSize == null || batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        }
    }
}
