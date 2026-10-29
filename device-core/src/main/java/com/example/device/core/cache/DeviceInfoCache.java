package com.example.device.core.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 设备缓存的唯一读写入口：除本类之外，任何组件不得直接触碰 {@code device:cache:info:*}。
 * <p>
 * 三条硬约束落在这里：TTL 必带随机抖动（防雪崩）、空值哨兵（防穿透）、重建互斥锁（防击穿）。
 * 写侧只做 {@code DEL}，从不做 {@code SET} —— 更新值会与并发写竞争出旧值（Redis 规范 §2.2【强制】）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DeviceInfoCache {

    private static final Logger log = LoggerFactory.getLogger(DeviceInfoCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;

    /**
     * @param redisTemplate 字符串模板，Value 为 JSON 文本
     * @param objectMapper  与对外响应同一个实例，保证缓存与响应的时间格式/命名策略不漂移
     * @param properties    TTL 与自旋参数
     */
    public DeviceInfoCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                           CacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 点查一个 SN 的缓存。
     *
     * @param sn 设备序列号
     * @return 缓存结局，永不为 {@code null}
     */
    public CacheResult get(String sn) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(CacheKeys.info(sn));
        } catch (DataAccessException e) {
            log.warn("cache read failed, sn={}, degrade to direct db query without backfill", sn, e);
            return new CacheResult(CacheResult.Status.DEGRADED, null);
        }
        return decode(sn, raw);
    }

    /**
     * 批量点查：一次 {@code MGET}，禁止循环 {@code GET}（Redis 规范 §1.4【强制】）。
     *
     * @param sns 设备序列号列表，顺序即返回 map 的迭代顺序
     * @return {@code sn -> 结局}；Redis 异常时全部标记为 {@link CacheResult.Status#DEGRADED}
     */
    public Map<String, CacheResult> getMulti(List<String> sns) {
        List<String> keys = sns.stream().map(CacheKeys::info).toList();
        List<String> raws;
        try {
            raws = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException e) {
            log.warn("cache multi read failed, size={}, degrade to direct db query", sns.size(), e);
            return degradedAll(sns);
        }
        Map<String, CacheResult> out = new LinkedHashMap<>(sns.size());
        for (int i = 0; i < sns.size(); i++) {
            String raw = raws == null || i >= raws.size() ? null : raws.get(i);
            out.put(sns.get(i), decode(sns.get(i), raw));
        }
        return out;
    }

    /**
     * 回填一条缓存，TTL = base + random(0..jitter)。
     *
     * @param sn    设备序列号
     * @param value 缓存值
     */
    public void put(String sn, DeviceCacheValue value) {
        String json = writeJson(sn, value);
        if (json == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(CacheKeys.info(sn), json, nextTtl());
        } catch (DataAccessException e) {
            log.warn("cache backfill failed, sn={}", sn, e);
        }
    }

    /**
     * 批量回填：一次 Pipeline，禁止逐条 {@code SET}。
     *
     * @param values {@code sn -> 值}
     */
    public void putMulti(Map<String, DeviceCacheValue> values) {
        if (values.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(values.size());
        List<String> jsons = new ArrayList<>(values.size());
        List<Long> ttls = new ArrayList<>(values.size());
        for (Map.Entry<String, DeviceCacheValue> e : values.entrySet()) {
            String json = writeJson(e.getKey(), e.getValue());
            if (json != null) {
                keys.add(CacheKeys.info(e.getKey()));
                jsons.add(json);
                ttls.add(nextTtl().toSeconds());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    for (int i = 0; i < keys.size(); i++) {
                        operations.opsForValue().set(keys.get(i), jsons.get(i), Duration.ofSeconds(ttls.get(i)));
                    }
                    return null;
                }
            });
        } catch (DataAccessException e) {
            log.warn("cache pipeline backfill failed, size={}", keys.size(), e);
        }
    }

    /**
     * 写入空值哨兵（设备不存在或已逻辑删除），TTL 远短于正常值。
     *
     * @param sn 设备序列号
     */
    public void putEmpty(String sn) {
        try {
            redisTemplate.opsForValue().set(CacheKeys.info(sn), CacheKeys.EMPTY_SENTINEL,
                    properties.emptyTtlOrDefault());
        } catch (DataAccessException e) {
            log.warn("cache empty-sentinel write failed, sn={}", sn, e);
        }
    }

    /**
     * 删除缓存；写侧失效与 EP-007 对账<b>只允许</b>这一个修复动作。
     * 失败只 WARN：TTL 与对账任务是兜底，不能因为 Redis 抖动让消费线程无限重投。
     *
     * @param sn 设备序列号
     */
    public void evict(String sn) {
        try {
            redisTemplate.delete(CacheKeys.info(sn));
        } catch (DataAccessException e) {
            log.warn("cache evict failed, sn={}, rely on ttl and reconcile job", sn, e);
        }
    }

    /**
     * 批量删除：一次 Pipeline {@code DEL}。
     *
     * @param sns 设备序列号列表
     */
    public void evictMulti(List<String> sns) {
        if (sns.isEmpty()) {
            return;
        }
        List<String> keys = sns.stream().map(CacheKeys::info).toList();
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    operations.delete(keys);
                    return null;
                }
            });
        } catch (DataAccessException e) {
            log.warn("cache pipeline evict failed, size={}", sns.size(), e);
        }
    }

    /**
     * 抢占缓存重建互斥锁（防击穿）。
     *
     * @param sn 设备序列号
     * @return true 表示本线程负责回源与回填；false 表示应自旋等待或降级直查
     */
    public boolean tryRebuildLock(String sn) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(CacheKeys.rebuildLock(sn), "1",
                            Duration.ofSeconds(CacheKeys.REBUILD_LOCK_TTL_SECONDS)));
        } catch (DataAccessException e) {
            log.warn("rebuild lock unavailable, sn={}, skip cache rebuild", sn, e);
            return false;
        }
    }

    /**
     * 释放重建锁。回源失败也要释放，否则该 SN 在 TTL 内所有人都走降级直查。
     *
     * @param sn 设备序列号
     */
    public void releaseRebuildLock(String sn) {
        try {
            redisTemplate.delete(CacheKeys.rebuildLock(sn));
        } catch (DataAccessException e) {
            log.warn("rebuild lock release failed, sn={}, ttl will reclaim it", sn, e);
        }
    }

    /**
     * 本次回填使用的 TTL：基础值加随机抖动，防同批 key 同时过期。
     *
     * @return 带抖动的 TTL
     */
    public Duration nextTtl() {
        long base = properties.ttlBaseOrDefault().toSeconds();
        long jitter = properties.ttlJitterOrDefault().toSeconds();
        long extra = jitter <= 0 ? 0L : ThreadLocalRandom.current().nextLong(0L, jitter + 1L);
        return Duration.ofSeconds(base + extra);
    }

    private CacheResult decode(String sn, String raw) {
        if (raw == null) {
            return new CacheResult(CacheResult.Status.MISS, null);
        }
        if (CacheKeys.EMPTY_SENTINEL.equals(raw)) {
            return new CacheResult(CacheResult.Status.EMPTY_SENTINEL, null);
        }
        try {
            return new CacheResult(CacheResult.Status.HIT, objectMapper.readValue(raw, DeviceCacheValue.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("cache value unreadable, sn={}, treat as miss so rebuild overwrites it", sn, e);
            return new CacheResult(CacheResult.Status.MISS, null);
        }
    }

    private String writeJson(String sn, DeviceCacheValue value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("cache value not serializable, sn={}, skip backfill", sn, e);
            return null;
        }
    }

    private Map<String, CacheResult> degradedAll(List<String> sns) {
        Map<String, CacheResult> out = new LinkedHashMap<>(sns.size());
        CacheResult degraded = new CacheResult(CacheResult.Status.DEGRADED, null);
        for (String sn : sns) {
            out.put(sn, degraded);
        }
        return out;
    }
}
