package com.example.device.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 本域指标的唯一出口，命名前缀统一 {@code device_ingest_} / {@code device_cache_}。
 * <p>
 * 只登记 {@code tech-design} §11.4 列出的 6 项可观测面（落地延迟、消费 lag、乐观锁冲突率、重复率、
 * 未授权来源计数、缓存命中率）。<b>不新增指标键</b>：加指标要先回写设计文档，否则文档与线上面板会分叉。
 * </p>
 * <p>
 * {@code unauthorizedSource} 的标签基数由配置表约束（维度 × source，量级 ≤ 数十），
 * 因此可以带标签；消息内容一律不进标签，否则一个畸形 {@code msg_type} 就能撑爆 registry。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DeviceIngestMetrics {

    /** 乐观锁冲突计数（冲突率分子）。 */
    public static final String CAS_CONFLICT = "device_ingest_cas_conflict_total";

    /** 重复消息计数（重复率分子）。 */
    public static final String DUPLICATE = "device_ingest_duplicate_total";

    /** 未授权来源计数。 */
    public static final String UNAUTHORIZED = "device_ingest_unauthorized_source_total";

    /** 未知消息类型计数。 */
    public static final String UNKNOWN_MSG_TYPE = "device_ingest_unknown_msg_type_total";

    /** 应用成功计数（冲突率/重复率的分母）。 */
    public static final String APPLIED = "device_ingest_applied_total";

    /** 单条消息落库耗时（落地延迟）。 */
    public static final String APPLY_DURATION = "device_ingest_apply_duration";

    /** 缓存读取结局计数。 */
    public static final String CACHE_LOOKUP = "device_cache_lookup_total";

    private final MeterRegistry registry;

    /**
     * @param registry Micrometer 注册表，由 Actuator 暴露
     */
    public DeviceIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 计数一次乐观锁冲突。
     */
    public void casConflict() {
        counter(CAS_CONFLICT).increment();
    }

    /**
     * 计数一次重复消息（唯一索引命中）。
     */
    public void duplicate() {
        counter(DUPLICATE).increment();
    }

    /**
     * 计数一次未授权来源被拒绝。
     *
     * @param dim    维度
     * @param source 来源编码
     */
    public void unauthorizedSource(String dim, String source) {
        Counter.builder(UNAUTHORIZED).tag("dim", dim).tag("source", source).register(registry).increment();
    }

    /**
     * 计数一次未知消息类型。
     *
     * @param msgType 类型原值；为避免无界标签基数，只作为日志字段，不进标签
     */
    public void unknownMsgType(String msgType) {
        counter(UNKNOWN_MSG_TYPE).increment();
    }

    /**
     * 计数一次成功落地。
     */
    public void applied() {
        counter(APPLIED).increment();
    }

    /**
     * 记录一次落地耗时。
     *
     * @param elapsed 耗时
     */
    public void recordApplyDuration(Duration elapsed) {
        Timer.builder(APPLY_DURATION).register(registry).record(elapsed.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 记录一次缓存读取结局。
     *
     * @param result {@code hit} / {@code empty} / {@code miss} / {@code degraded}
     */
    public void cacheLookup(String result) {
        Counter.builder(CACHE_LOOKUP).tag("result", result).register(registry).increment();
    }

    private Counter counter(String name) {
        return Counter.builder(name).register(registry);
    }
}
