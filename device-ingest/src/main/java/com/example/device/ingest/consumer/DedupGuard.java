package com.example.device.ingest.consumer;

import com.example.device.core.cache.CacheKeys;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 第一层幂等：Redis {@code SETNX} 挡并发重投。
 * <p>
 * 它<b>不是</b>正确性依据——正确性由 {@code mq_consume_record.uk_source_msg} 唯一索引承担
 * （{@code tech-design} TD-002）。因此本类的两条降级路径都必须"放行"而不是"拦下"：
 * Redis 异常时 {@link #tryMark} 返回 {@code true}，让 DB 层做最终裁决。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DedupGuard {

    private static final Logger log = LoggerFactory.getLogger(DedupGuard.class);

    /** 占位值：键存在即代表该消息已被占有，无需真实内容。 */
    private static final String MARK = "1";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * @param redisTemplate 字符串模板
     * @param dedupTtlHours 键 TTL 小时数，来自 {@code mq.dedup-ttl-hours}
     */
    public DedupGuard(StringRedisTemplate redisTemplate,
                      @Value("${mq.dedup-ttl-hours:24}") long dedupTtlHours) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(dedupTtlHours);
    }

    /**
     * 占有这条消息。
     *
     * @param source 来源编码
     * @param msgId  消息 ID
     * @return {@code true} 表示首次占有成功或 Redis 不可用（放行，交给 DB 判重）；
     *         {@code false} 表示键已存在，判为重复
     */
    public boolean tryMark(String source, String msgId) {
        try {
            Boolean marked = redisTemplate.opsForValue().setIfAbsent(CacheKeys.dedup(source, msgId), MARK, ttl);
            return Boolean.TRUE.equals(marked);
        } catch (DataAccessException e) {
            log.warn("dedup redis unavailable, fall back to db unique index, source={}, msg_id={}", source, msgId, e);
            return true;
        }
    }

    /**
     * 释放占有：<b>回滚路径必须调用</b>（R3）。
     * <p>
     * 带着键重试会被 {@link #tryMark} 挡成"重复"直接 ack，消息被吞且不进死信——这是 R3 存在的原因。
     * 判为重复（DB 层 {@code DUPLICATE}）的路径<b>禁止</b>调用本方法。
     * </p>
     *
     * @param source 来源编码
     * @param msgId  消息 ID
     */
    public void release(String source, String msgId) {
        try {
            redisTemplate.delete(CacheKeys.dedup(source, msgId));
        } catch (DataAccessException e) {
            log.warn("dedup release failed, source={}, msg_id={}, the key expires by itself after {}", source,
                    msgId, ttl, e);
        }
    }
}
