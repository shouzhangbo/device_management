package com.example.device.core.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 消费 lag 指标（{@code tech-design} §11.1：唯一能提前发现"对外数据开始滞后"的信号）。
 * <p>
 * 本类<b>不依赖 spring-kafka</b>：core 没引 Kafka 依赖（{@code tech-design} §12.4），
 * lag 值由 ingest 侧的容器回调 {@link #report(long)} 推进来，core 只负责聚合与暴露。
 * 阈值 {@code device.ingest.lag-alarm} 在这里做成可查的 gauge 标签值，
 * 让告警规则能直接比较 {@code device_ingest_lag_max > device_ingest_lag_alarm_threshold}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class LagMonitor {

    /** 当前最大分区 lag。 */
    public static final String LAG_MAX = "device_ingest_lag_max";

    /** 告警阈值，随配置刷新。 */
    public static final String LAG_THRESHOLD = "device_ingest_lag_alarm_threshold";

    private final AtomicLong maxLag = new AtomicLong(0L);
    private final AtomicLong threshold = new AtomicLong(10_000L);

    /**
     * @param registry 注册表
     */
    public LagMonitor(MeterRegistry registry) {
        Gauge.builder(LAG_MAX, maxLag, AtomicLong::doubleValue).register(registry);
        Gauge.builder(LAG_THRESHOLD, threshold, AtomicLong::doubleValue).register(registry);
    }

    /**
     * 上报本轮观测到的最大 lag。
     *
     * @param lag 条数，负值视为 0（时钟/位点抖动可能算出负数）
     */
    public void report(long lag) {
        maxLag.set(Math.max(0L, lag));
    }

    /**
     * 设置告警阈值，供配置加载后调用一次。
     *
     * @param thresholdLag 阈值条数
     */
    public void setThreshold(long thresholdLag) {
        threshold.set(Math.max(1L, thresholdLag));
    }

    /**
     * 是否已超过告警阈值，供日志与健康检查复用，避免两处各写一遍比较逻辑。
     *
     * @return true 表示数据开始滞后
     */
    public boolean isOverThreshold() {
        return maxLag.get() > threshold.get();
    }
}
