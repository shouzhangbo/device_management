package com.example.device.ingest.dlq;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.contract.ValidatedCommand;
import com.example.device.core.entity.MqDeadLetterDO;
import com.example.device.core.mapper.MqDeadLetterMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 死信归档：把原始消息<b>原封不动</b>写进 {@code mq_dead_letter(status=0)}（R12）。
 * <p>
 * {@code content} 存 UTF-8 解码后的原文，因此框架层必须用 {@code ByteArrayDeserializer}——
 * 用 {@code JsonDeserializer} 会在解析失败时连原文都拿不到，死信就无法保存内容。
 * </p>
 * <p>
 * 写失败<b>吞掉异常并返回 {@code false}</b>：调用方据此不 ack、让消息重投。这里不抛异常，
 * 是为了让"落死信失败"与"业务失败"在调用方是两个不同分支，而不是靠异常类型猜。
 * </p>
 * <p>
 * 不新增指标键：设计 §11.1 只登记了死信表的行数口径（正常 &lt; 1000 行），
 * 因此这里只打 WARN/ERROR 日志，计数由 {@code mq_dead_letter} 自身承载。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DeadLetterWriter {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterWriter.class);

    /**
     * {@code mq_dead_letter.content} 是 {@code TEXT}，上限 65535 字节，而消息体上限是 256KB。
     * 不截断会让"超限消息"在落死信时再失败一次 → 永远不 ack → 毒丸堵住整个分区，
     * 因此超长内容截断落库并打 ERROR（截断事实必须可查）。
     */
    private static final int MAX_CONTENT_BYTES = 65_535;

    private final MqDeadLetterMapper deadLetterMapper;
    private final String consumerGroup;

    /**
     * @param deadLetterMapper 死信持久层
     * @param consumerGroup    消费组名，来自 {@code spring.kafka.consumer.group-id}
     */
    public DeadLetterWriter(MqDeadLetterMapper deadLetterMapper,
                            @Value("${spring.kafka.consumer.group-id:device-management-ingest}") String consumerGroup) {
        this.deadLetterMapper = deadLetterMapper;
        this.consumerGroup = consumerGroup;
    }

    /**
     * 归档一条"连信封都解析不出来"的消息。
     *
     * @param record     原始记录
     * @param reason     失败原因
     * @param retryCount 已重试次数
     * @return true 表示已落库，可以 ack；false 表示落库失败，禁止 ack
     */
    public boolean write(ConsumerRecord<byte[], byte[]> record, String reason, int retryCount) {
        return write(record, null, null, reason, retryCount);
    }

    /**
     * 归档一条语义合法但写入失败的消息。
     *
     * @param record     原始记录
     * @param command    已校验的命令，用于回填 source / msg_id / sn
     * @param reason     失败原因
     * @param retryCount 已重试次数
     * @return true 表示已落库，可以 ack；false 表示落库失败，禁止 ack
     */
    public boolean write(ConsumerRecord<byte[], byte[]> record, ValidatedCommand command, String reason,
                         int retryCount) {
        return write(record, command.source(), command.msgId(), reason, retryCount);
    }

    private boolean write(ConsumerRecord<byte[], byte[]> record, String source, String msgId, String reason,
                          int retryCount) {
        MqDeadLetterDO row = new MqDeadLetterDO();
        row.setMsgId(msgId == null || msgId.isBlank() ? safeString(record.key()) : msgId);
        row.setSource(source == null ? "" : source);
        row.setTopic(record.topic() == null ? "" : record.topic());
        row.setConsumerGroup(consumerGroup);
        row.setPartitionNo(record.partition());
        row.setOffsetNo(record.offset());
        row.setMsgKey(safeString(record.key()));
        row.setContent(contentOf(record.value()));
        row.setFailReason(PayloadLimits.truncateFailReason(reason));
        row.setRetryCount(retryCount);
        row.setStatus(0);
        try {
            deadLetterMapper.insert(row);
            log.warn("dead letter archived, topic={}, partition={}, offset={}, retry={}, reason={}",
                    record.topic(), record.partition(), record.offset(), retryCount, reason);
            return true;
        } catch (DataAccessException e) {
            log.error("dead letter archiving failed, message will be redelivered, topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
            return false;
        }
    }

    private static String safeString(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String contentOf(byte[] value) {
        if (value == null) {
            return "";
        }
        if (value.length <= MAX_CONTENT_BYTES) {
            return new String(value, StandardCharsets.UTF_8);
        }
        String head = new String(value, 0, MAX_CONTENT_BYTES, StandardCharsets.UTF_8);
        if (!head.isEmpty() && head.charAt(head.length() - 1) == '\uFFFD') {
            head = head.substring(0, head.length() - 1);
        }
        log.error("dead letter content truncated, originalBytes={}, keptBytes<={}", value.length, MAX_CONTENT_BYTES);
        return head;
    }
}
