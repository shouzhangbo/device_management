package com.example.device.core.service;

import com.example.device.core.cache.CacheKeys;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 后台建档的 {@code Idempotency-Key} 去重（HTTP 规范 §9.1【强制】）。
 * <p>
 * {@code uk_sn} 只能挡"不同请求撞同一 SN"，挡不住"同一请求被重发"——后者应返回<b>同一结果</b>
 * 而不是 {@code 409/4090}（{@code tech-design} §9.4 幂等键行）。因此这里存的是首次执行的结果快照，
 * 键 {@code device:admin:idem:{key}}，TTL {@code device.admin.idem-ttl-hours}。
 * </p>
 * <p>
 * <b>失败必须释放占位</b>：首次执行抛异常时删键，否则调用方带同一个 key 重试会被永久挡在门外。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class IdempotencyKeyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyKeyGuard.class);

    /** 占位值：表示"同一个 key 的首次请求仍在执行中"。 */
    private static final String IN_FLIGHT = "__IN_FLIGHT__";

    /**
     * 等待首次结果就绪的有界自旋：次数与间隔都是进程内常量，不是配置键
     * （设计文档只为"快照 TTL"登记了 {@code device.admin.idem-ttl-hours}，未为等待开新键）。
     */
    private static final int WAIT_TIMES = 3;

    private static final long WAIT_MILLIS = 200L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration snapshotTtl;

    /**
     * @param redisTemplate Redis 模板
     * @param objectMapper  与响应同一个映射器，快照的字段命名策略不能与出口不一致
     * @param ttlHours      {@code device.admin.idem-ttl-hours}，缺省 24
     */
    public IdempotencyKeyGuard(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                               @Value("${device.admin.idem-ttl-hours:24}") int ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.snapshotTtl = Duration.ofHours(ttlHours <= 0 ? 24 : ttlHours);
    }

    /**
     * 以幂等键执行一次写操作。
     *
     * @param idempotencyKey 请求头 {@code Idempotency-Key}，非空由入口过滤器保证
     * @param resultType     结果类型，用于反序列化首次快照
     * @param action         首次执行的动作
     * @param <T>            结果类型
     * @return 首次结果，或重放请求命中的同一份快照
     * @throws BizException 首次请求仍在执行中（{@code 409}）
     */
    public <T> T runIfNew(String idempotencyKey, Class<T> resultType, Supplier<T> action) {
        String key = CacheKeys.adminIdem(idempotencyKey);
        Boolean claimed = claim(key);
        if (Boolean.TRUE.equals(claimed)) {
            T result;
            try {
                result = action.get();
            } catch (RuntimeException e) {
                release(key);
                throw e;
            }
            saveSnapshot(key, result);
            return result;
        }
        T replayed = awaitSnapshot(key, resultType);
        if (replayed != null) {
            LOG.info("idempotent replay hit, key={}", idempotencyKey);
            return replayed;
        }
        throw new BizException(ErrorCode.SN_ALREADY_EXISTS,
                "a request with this Idempotency-Key is still in progress");
    }

    private Boolean claim(String key) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, IN_FLIGHT, snapshotTtl);
        } catch (DataAccessException e) {
            // Redis 不可用时不阻断建档：uk_sn 仍能挡住真正的重复提交，只是丢一次"同结果重放"的能力。
            LOG.warn("idempotency claim failed, fall back to uk_sn protection only", e);
            return Boolean.TRUE;
        }
    }

    private void release(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            LOG.warn("idempotency release failed, key={}", key, e);
        }
    }

    private <T> void saveSnapshot(String key, T result) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), snapshotTtl);
        } catch (DataAccessException | JsonProcessingException e) {
            LOG.warn("idempotency snapshot write failed, key={}", key, e);
        }
    }

    private <T> T awaitSnapshot(String key, Class<T> resultType) {
        for (int i = 0; i < WAIT_TIMES; i++) {
            String raw = read(key);
            if (raw == null) {
                return null;
            }
            if (!IN_FLIGHT.equals(raw)) {
                return deserialize(raw, resultType);
            }
            if (!sleepBriefly()) {
                return null;
            }
        }
        return null;
    }

    private String read(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            LOG.warn("idempotency read failed, key={}", key, e);
            return null;
        }
    }

    private <T> T deserialize(String raw, Class<T> resultType) {
        try {
            return objectMapper.readValue(raw, resultType);
        } catch (JsonProcessingException e) {
            LOG.warn("idempotency snapshot unreadable, bytes={}", raw.length(), e);
            return null;
        }
    }

    private static boolean sleepBriefly() {
        try {
            Thread.sleep(WAIT_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
