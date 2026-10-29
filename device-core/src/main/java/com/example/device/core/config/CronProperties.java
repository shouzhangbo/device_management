package com.example.device.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device.cron.*} 的类型安全绑定（EP-007 / EP-008 的互斥锁与水位参数）。
 * <p>
 * 三个键都在 {@code tech-design} §11.1 的 G-002 表登记过，本期不再新增。
 * {@code lock-ttl-ratio} 与 {@code lock-safety-margin-ratio} 属<b>同一启动断言组</b>：
 * 两者都合法（{@code 0 < ratio < 1}）才允许定时任务注册。
 * </p>
 *
 * @param lockTtlRatio        互斥锁 TTL 占调度周期的比例
 * @param lockSafetyMarginRatio 剩余锁时长低于 {@code TTL × 该值} 时主动截断本轮
 * @param watermarkTtlHours   对账水位 key 的 TTL（小时）
 * @author houzhangbo
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "device.cron")
public record CronProperties(Double lockTtlRatio, Double lockSafetyMarginRatio, Integer watermarkTtlHours) {

    /** 建议值 0.8。 */
    public static final double DEFAULT_LOCK_TTL_RATIO = 0.8D;

    /** 建议值 0.2。 */
    public static final double DEFAULT_SAFETY_MARGIN_RATIO = 0.2D;

    /** 建议值 168 小时（7 天）。 */
    public static final int DEFAULT_WATERMARK_TTL_HOURS = 168;

    /**
     * 锁 TTL 比例。
     *
     * @return 生效比例
     */
    public double lockTtlRatioOrDefault() {
        return validRatio(lockTtlRatio) ? lockTtlRatio : DEFAULT_LOCK_TTL_RATIO;
    }

    /**
     * 截断余量比例。
     *
     * @return 生效比例
     */
    public double safetyMarginRatioOrDefault() {
        return validRatio(lockSafetyMarginRatio) ? lockSafetyMarginRatio : DEFAULT_SAFETY_MARGIN_RATIO;
    }

    /**
     * 水位 key 的 TTL。
     *
     * @return 生效 TTL
     */
    public Duration watermarkTtlOrDefault() {
        return Duration.ofHours(watermarkTtlHours == null || watermarkTtlHours <= 0
                ? DEFAULT_WATERMARK_TTL_HOURS : watermarkTtlHours);
    }

    /**
     * 断言用的原始比例判定：不做回落，缺键或越界都要让启动断言失败。
     *
     * @param ratio 待检值
     * @return 是否落在 {@code (0,1)} 开区间
     */
    public static boolean validRatio(Double ratio) {
        return ratio != null && ratio > 0 && ratio < 1;
    }
}
