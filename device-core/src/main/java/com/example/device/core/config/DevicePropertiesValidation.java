package com.example.device.core.config;

import com.example.device.core.cache.CacheProperties;
import com.example.device.core.query.QueryProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动断言：把 {@code tech-design} §11.1 登记的不变式变成"不满足就拒绝启动"，而不是等运行时出问题。
 * <p>
 * 断言失败一律抛 {@link IllegalStateException} 让上下文启动失败。理由：这些不等式一旦违反，
 * 表现是<b>消费线程被踢出消费组</b>、<b>无界自旋</b>或<b>锁先过期导致双实例并发跑</b>，
 * 都属于静默的结构性错误，降级运行只会把问题推到生产。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DevicePropertiesValidation {

    private static final Logger LOG = LoggerFactory.getLogger(DevicePropertiesValidation.class);

    private final DeviceIngestProperties ingest;
    private final CacheProperties cache;
    private final QueryProperties query;
    private final CronProperties cron;
    private final long maxPollIntervalMs;

    /**
     * @param ingest            消费侧参数
     * @param cache             缓存参数
     * @param query             读侧参数
     * @param cron              定时任务参数
     * @param maxPollIntervalMs {@code spring.kafka.consumer.max-poll-interval-ms}（Spring Boot 标准键，不新造）
     */
    public DevicePropertiesValidation(DeviceIngestProperties ingest, CacheProperties cache,
                                     QueryProperties query, CronProperties cron,
                                     @Value("${spring.kafka.consumer.max-poll-interval-ms:300000}")
                                     long maxPollIntervalMs) {
        this.ingest = ingest;
        this.cache = cache;
        this.query = query;
        this.cron = cron;
        this.maxPollIntervalMs = maxPollIntervalMs;
    }

    /**
     * 执行全部断言。
     */
    @PostConstruct
    public void validate() {
        require(ingest.getRetryBudgetMs() < maxPollIntervalMs,
                "device.ingest.retry-budget-ms(" + ingest.getRetryBudgetMs()
                        + ") must be < spring.kafka.consumer.max-poll-interval-ms(" + maxPollIntervalMs
                        + "): 重试预算超过拉取间隔会把消费线程踢出消费组");
        require(ingest.getConcurrency() <= ingest.getPartition(),
                "device.ingest.concurrency(" + ingest.getConcurrency() + ") must be <= device.ingest.partition("
                        + ingest.getPartition() + "): 多出来的线程只会空等分区");
        require(ingest.getMaxRetry() > 0, "device.ingest.max-retry must be > 0");
        require(ingest.getTxTimeoutSec() > 0, "device.ingest.tx-timeout-sec must be > 0");
        require(ingest.getBackoffMs() > 0, "device.ingest.backoff-ms must be > 0");
        require(CronProperties.validRatio(ingest.getBackoffJitter()),
                "device.ingest.backoff-jitter must be within (0,1): 无 jitter 的同 SN 重试会同步共振");
        require(ingest.getMaxPayloadBytes() > 0, "device.ingest.max-payload-bytes must be > 0");

        require(positive(cache.ttlBaseOrDefault()), "device.cache.ttl-base must be positive");
        require(!cache.ttlJitterOrDefault().isNegative(), "device.cache.ttl-jitter must not be negative");
        require(positive(cache.emptyTtlOrDefault()), "device.cache.empty-ttl must be positive");
        require(cache.reconcile() == null || cache.reconcile().batchSizeOrDefault() > 0,
                "device.cache.reconcile.batch-size must be > 0");

        require(query.batchMaxOrDefault() > 0, "device.query.batch-max must be > 0");

        require(CronProperties.validRatio(cron.lockTtlRatio()),
                "device.cron.lock-ttl-ratio must be within (0,1): 比例越界会让锁 TTL 大于周期或为零");
        require(CronProperties.validRatio(cron.lockSafetyMarginRatio()),
                "device.cron.lock-safety-margin-ratio must be within (0,1): 与 lock-ttl-ratio 同组断言");
        require(cron.watermarkTtlOrDefault().compareTo(Duration.ofHours(1)) >= 0,
                "device.cron.watermark-ttl-hours must cover the reconcile period");

        LOG.info("device.* configuration assertions passed: retryBudget={}ms maxPollInterval={}ms "
                        + "concurrency={} partition={} batchMax={}",
                ingest.getRetryBudgetMs(), maxPollIntervalMs, ingest.getConcurrency(),
                ingest.getPartition(), query.batchMaxOrDefault());
    }

    private static boolean positive(Duration d) {
        return d != null && d.compareTo(Duration.ZERO) > 0;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("configuration assertion failed: " + message);
        }
    }
}
