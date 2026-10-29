package com.example.device.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device.ingest.*} 的类型安全绑定。
 * <p>
 * 用"类 + 字段初始值"而不是 record：record 的构造绑定在键缺失时给 {@code null}，
 * 每个键都要再写一个 {@code xxxOrDefault()}；字段初始值天然就是"设计文档的建议值"，
 * 缺键时进程按建议值运行，配置写错时由 {@code DevicePropertiesValidation} 在启动期断言拦下。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "device.ingest")
@lombok.Getter
@lombok.Setter
public class DeviceIngestProperties {

    /** 消费者并发数，必须 ≤ Topic 分区数（§11.1）。 */
    private int concurrency = 3;

    /** Topic 分区数，即消费并行上限。 */
    private int partition = 6;

    /** 乐观锁冲突的最大重试次数，超限进死信。 */
    private int maxRetry = 3;

    /** 指数退避基数毫秒。 */
    private long backoffMs = 50L;

    /** 退避抖动比例：缺 jitter 时并发同 SN 的重试会共振、反复撞同一版本。 */
    private double backoffJitter = 0.2D;

    /** 单次消息处理的总重试预算毫秒，必须 < {@code max.poll.interval.ms}。 */
    private long retryBudgetMs = 1500L;

    /** 写事务超时秒数。 */
    private int txTimeoutSec = 3;

    /** 消费 lag 告警阈值条数。 */
    private long lagAlarm = 10_000L;

    /** 单条消息体上限字节，超限直接落死信。 */
    private int maxPayloadBytes = 262_144;
}
