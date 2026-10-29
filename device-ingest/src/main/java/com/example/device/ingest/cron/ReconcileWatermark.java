package com.example.device.ingest.cron;

import com.example.device.core.cache.CacheKeys;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * EP-007 对账水位：{@code device:cron:watermark:cache-reconcile}，值是上轮已处理行的 {@code update_time}。
 * <p>
 * 不新建状态表：设计未授权新增表，而水位只需要一个单值；Redis 规范 §2.3【强制】要求键必须有 TTL，
 * 因此每轮 {@link #save} 都续期。
 * </p>
 * <p>
 * 水位丢失时以 {@code now - 2 × 周期} 冷启动，<b>禁止</b>用 {@code 0} 冷启动——那等于首轮全表扫，
 * 对账自己就成了写放大源（{@code tech-design} §5.2 EP-007）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class ReconcileWatermark {

    private static final Logger log = LoggerFactory.getLogger(ReconcileWatermark.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * @param redisTemplate  字符串模板
     * @param watermarkTtlHours 水位键 TTL 小时数，来自 {@code device.cron.watermark-ttl-hours}
     */
    public ReconcileWatermark(StringRedisTemplate redisTemplate,
                              @Value("${device.cron.watermark-ttl-hours:168}") long watermarkTtlHours) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(watermarkTtlHours);
    }

    /**
     * 读上轮水位。
     *
     * @param period 对账周期，用于键丢失时的冷启动偏移
     * @return 水位；键不存在、值不可解析或 Redis 不可用时返回 {@code now - 2 × period}
     */
    public LocalDateTime load(Duration period) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(CacheKeys.RECONCILE_WATERMARK);
        } catch (DataAccessException e) {
            log.warn("watermark read failed, cold start with now - 2 x period", e);
            return coldStart(period);
        }
        if (raw == null || raw.isBlank()) {
            log.info("watermark absent, cold start with now - 2 x period, period={}", period);
            return coldStart(period);
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            log.warn("watermark malformed, cold start with now - 2 x period, raw={}", raw, e);
            return coldStart(period);
        }
    }

    /**
     * 推进水位并续期。
     *
     * @param watermark 已处理到的 {@code update_time}
     */
    public void save(LocalDateTime watermark) {
        if (watermark == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(CacheKeys.RECONCILE_WATERMARK,
                    watermark.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), ttl);
        } catch (DataAccessException e) {
            log.warn("watermark save failed, next round rescans a bigger window, watermark={}", watermark, e);
        }
    }

    private static LocalDateTime coldStart(Duration period) {
        return LocalDateTime.now().minus(period.multipliedBy(2));
    }
}
