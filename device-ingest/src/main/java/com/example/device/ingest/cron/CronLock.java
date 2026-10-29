package com.example.device.ingest.cron;

import com.example.device.core.cache.CacheKeys;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 定时任务的跨实例互斥：{@code SETNX device:lock:cron:{job} EX TTL}。
 * <p>
 * 没有它，多实例部署时同一批 SN 会被重复 MGET/DEL、水位互相覆盖，{@code DROP PARTITION} 还会撞 DDL 元数据锁。
 * 这是映射为消除设计缺口而新增的防线（{@code tech-design} §5.2 G-001）。
 * </p>
 * <p>
 * {@link #unlock} 直接删除而不做 token 比对：锁 TTL 已按"周期 × 0.8"取值，且任务体在剩余时长低于
 * {@code TTL × 0.2} 时主动截断，正常情况下不会出现"锁已过期、本实例还在跑"再误删他人锁的窗口。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class CronLock {

    private static final Logger log = LoggerFactory.getLogger(CronLock.class);

    private static final String TOKEN = "1";

    private final StringRedisTemplate redisTemplate;

    /**
     * @param redisTemplate 字符串模板
     */
    public CronLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试抢锁。
     *
     * @param job 任务名，取值限于 {@code cache-reconcile} / {@code archive-cleanup} / {@code partition-prebuild}
     * @param ttl 锁存活时长
     * @return true 表示抢到；false 表示别的实例在跑或 Redis 不可用（本轮直接跳过）
     */
    public boolean tryLock(String job, Duration ttl) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(CacheKeys.cronLock(job), TOKEN, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("cron lock unavailable, skip this round, job={}", job, e);
            return false;
        }
    }

    /**
     * 释放锁。
     *
     * @param job 任务名
     */
    public void unlock(String job) {
        try {
            redisTemplate.delete(CacheKeys.cronLock(job));
        } catch (DataAccessException e) {
            log.warn("cron lock release failed, job={}, it expires by its ttl", job, e);
        }
    }

    /**
     * 剩余锁时长，任务体据此判断是否需要主动截断本轮。
     *
     * @param job 任务名
     * @return 剩余毫秒数；键不存在时为 {@code -2}，无过期时间时为 {@code -1}，Redis 不可用时为 {@code 0}
     */
    public long remainingMillis(String job) {
        try {
            Long remaining = redisTemplate.getExpire(CacheKeys.cronLock(job), TimeUnit.MILLISECONDS);
            return remaining == null ? 0L : remaining;
        } catch (DataAccessException e) {
            log.warn("cron lock ttl read failed, treat as expired, job={}", job, e);
            return 0L;
        }
    }
}
