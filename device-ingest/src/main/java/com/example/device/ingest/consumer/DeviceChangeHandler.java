package com.example.device.ingest.consumer;

import com.example.device.core.applier.ApplyOutcome;
import com.example.device.core.applier.DeviceInfoApplier;
import com.example.device.core.auth.DimAuthGuard;
import com.example.device.core.config.DeviceIngestProperties;
import com.example.device.core.config.ShutdownPhaseConfig.InflightCounter;
import com.example.device.core.contract.EnvelopeParser;
import com.example.device.core.contract.EnvelopeValidator;
import com.example.device.core.contract.ParseResult;
import com.example.device.core.contract.ValidateResult;
import com.example.device.core.contract.ValidatedCommand;
import com.example.device.core.metrics.DeviceIngestMetrics;
import com.example.device.core.metrics.LagMonitor;
import com.example.device.ingest.dlq.DeadLetterWriter;
import java.time.Duration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * EP-006 消费主体：一条消息 → 一个终态 → 一次位点提交。
 * <p>
 * 全链路顺序固定为 尺寸/解析 → 校验 → 授权 → Redis 挡重 → 有界重试写入 → 终态（{@code tech-design} §9.3）。
 * 任何一步的失败都收敛为 {@link ConsumeOutcomeResolver.Outcome}，<b>本类不向上抛业务异常</b>：
 * 抛出会让容器无限重投并积压分区，而"该拒的消息"必须被安静地 ack 掉（R11）。
 * </p>
 * <p>
 * {@link DuplicateKeyException} 只在本类（事务外）翻译成 {@link ApplyOutcome#DUPLICATE}（R2），
 * 且<b>不释放</b> Redis 幂等键：它确实是一条重复消息。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DeviceChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceChangeHandler.class);

    /**
     * 来源 Topic。取自契约 {@code x-kafka-message-contracts.topic}，是<b>契约常量</b>而不是配置键
     * （{@code tech-design} §11.1 未登记 topic 配置项，凭空加键会造出无人维护的开关）。
     */
    public static final String TOPIC = "device.info.change";

    /** lag 上报间隔：消费者指标读取很廉价，但没必要每条消息都算。 */
    private static final long LAG_REPORT_INTERVAL_MS = 10_000L;

    /** Kafka 消费者自带的"最大分区 lag"指标名（KIP-92）。 */
    private static final String LAG_METRIC = "records-lag-max";

    private final EnvelopeParser parser;
    private final EnvelopeValidator validator;
    private final DimAuthGuard authGuard;
    private final DedupGuard dedupGuard;
    private final RetryBudget retryBudget;
    private final DeviceInfoApplier applier;
    private final DeadLetterWriter deadLetterWriter;
    private final ConsumeOutcomeResolver resolver;
    private final DeviceIngestMetrics metrics;
    private final LagMonitor lagMonitor;
    private final InflightCounter inflight;
    private final DeviceIngestProperties properties;

    private volatile long lastLagReportAt;

    /**
     * @param parser           契约解析
     * @param validator        维度与列白名单校验
     * @param authGuard        维度授权
     * @param dedupGuard       Redis 第一层幂等
     * @param retryBudget      事务外有界重试
     * @param applier          唯一事务入口
     * @param deadLetterWriter 死信归档
     * @param resolver         终态与 ack 判定
     * @param metrics          消费指标
     * @param lagMonitor       lag 上报出口
     * @param inflight         停机排空计数
     * @param properties       重试次数等参数
     */
    public DeviceChangeHandler(EnvelopeParser parser, EnvelopeValidator validator, DimAuthGuard authGuard,
                               DedupGuard dedupGuard, RetryBudget retryBudget, DeviceInfoApplier applier,
                               DeadLetterWriter deadLetterWriter, ConsumeOutcomeResolver resolver,
                               DeviceIngestMetrics metrics, LagMonitor lagMonitor, InflightCounter inflight,
                               DeviceIngestProperties properties) {
        this.parser = parser;
        this.validator = validator;
        this.authGuard = authGuard;
        this.dedupGuard = dedupGuard;
        this.retryBudget = retryBudget;
        this.applier = applier;
        this.deadLetterWriter = deadLetterWriter;
        this.resolver = resolver;
        this.metrics = metrics;
        this.lagMonitor = lagMonitor;
        this.inflight = inflight;
        this.properties = properties;
    }

    /**
     * 消费一条记录。
     * <p>
     * 参数 {@code consumer} 由 spring-kafka 注入，只用于读取 {@code records-lag-max} 指标，
     * 不参与业务逻辑；它为 {@code null} 时跳过 lag 上报，不影响消费。
     * </p>
     *
     * @param record   原始记录（字节，未反序列化）
     * @param ack      手动位点
     * @param consumer 当前消费线程的消费者，可为 {@code null}
     */
    @KafkaListener(topics = TOPIC)
    public void handle(ConsumerRecord<byte[], byte[]> record, Acknowledgment ack, Consumer<?, ?> consumer) {
        inflight.start();
        try {
            ConsumeOutcomeResolver.Outcome outcome = process(record);
            reportLag(consumer);
            if (outcome.shouldAck()) {
                ack.acknowledge();
            } else {
                log.error("outcome requires redelivery, offset not committed, topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            }
        } catch (RuntimeException e) {
            // 兜底不 ack：宁可重投也不丢消息。process 内部已把可判定的失败收敛为终态。
            log.error("unhandled consume failure, offset not committed, topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
        } finally {
            inflight.end();
        }
    }

    private ConsumeOutcomeResolver.Outcome process(ConsumerRecord<byte[], byte[]> record) {
        ParseResult parsed = parser.parse(record.value());
        if (parsed.isBad()) {
            log.warn("payload rejected before validation, status={}, reason={}", parsed.status(), parsed.reason());
            return resolver.resolveDeadLetter(deadLetterWriter.write(record, parsed.reason(), 0));
        }
        ValidateResult validated = validator.validate(parsed.envelope());
        ConsumeOutcomeResolver.Outcome early = resolver.resolveValidation(validated.status());
        if (early != null) {
            if (early == ConsumeOutcomeResolver.Outcome.UNKNOWN_MSG_TYPE) {
                metrics.unknownMsgType(parsed.envelope().msgType());
            }
            log.warn("message rejected by validator, status={}, msg_type={}, sn={}, reason={}",
                    validated.status(), parsed.envelope().msgType(), parsed.envelope().sn(), validated.reason());
            return early;
        }
        ValidatedCommand command = validated.command();
        ConsumeOutcomeResolver.Outcome unauthorized =
                resolver.resolveAuth(authGuard.check(command.dim(), command.source()));
        if (unauthorized != null) {
            metrics.unauthorizedSource(command.dim().name(), command.source());
            log.warn("unauthorized source, dim={}, source={}, sn={}, msg_id={}",
                    command.dim(), command.source(), command.sn(), command.msgId());
            return unauthorized;
        }
        if (!dedupGuard.tryMark(command.source(), command.msgId())) {
            metrics.duplicate();
            log.info("duplicate message filtered by redis, source={}, msg_id={}, sn={}",
                    command.source(), command.msgId(), command.sn());
            return ConsumeOutcomeResolver.Outcome.DUPLICATE;
        }
        long startedAt = System.nanoTime();
        try {
            ApplyOutcome applied = retryBudget.run(() -> applyOnce(command, record.topic()),
                    () -> dedupGuard.release(command.source(), command.msgId()));
            metrics.recordApplyDuration(Duration.ofNanos(System.nanoTime() - startedAt));
            if (applied == ApplyOutcome.APPLIED) {
                metrics.applied();
            }
            return resolver.resolve(applied);
        } catch (RetryBudget.ExhaustedException e) {
            metrics.recordApplyDuration(Duration.ofNanos(System.nanoTime() - startedAt));
            String reason = reasonOf(e);
            log.warn("retry exhausted, sn={}, msg_id={}, reason={}", command.sn(), command.msgId(), reason);
            return resolver.resolveDeadLetter(
                    deadLetterWriter.write(record, command, reason, properties.getMaxRetry()));
        }
    }

    /**
     * 一次事务尝试。{@code DuplicateKeyException} 在此翻译为返回值而不是继续上抛，
     * 否则 {@link RetryBudget} 会把"重复"当可重试失败，白白重试到预算耗尽再落死信。
     */
    private ApplyOutcome applyOnce(ValidatedCommand command, String topic) {
        try {
            return applier.apply(command.toApplyCommand(topic));
        } catch (DuplicateKeyException e) {
            return ApplyOutcome.DUPLICATE;
        }
    }

    private static String reasonOf(Throwable e) {
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : e.getMessage() + ": " + cause.getMessage();
    }

    private void reportLag(Consumer<?, ?> consumer) {
        long now = System.currentTimeMillis();
        if (consumer == null || now - lastLagReportAt < LAG_REPORT_INTERVAL_MS) {
            return;
        }
        lastLagReportAt = now;
        double maxLag = 0.0D;
        for (Metric metric : consumer.metrics().values()) {
            MetricName name = metric.metricName();
            if (LAG_METRIC.equals(name.name()) && metric.metricValue() instanceof Number value) {
                maxLag = Math.max(maxLag, value.doubleValue());
            }
        }
        lagMonitor.report((long) maxLag);
        if (lagMonitor.isOverThreshold()) {
            log.warn("consume lag over threshold, lag={}", (long) maxLag);
        }
    }
}
