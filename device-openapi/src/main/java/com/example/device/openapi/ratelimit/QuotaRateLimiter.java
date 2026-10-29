package com.example.device.openapi.ratelimit;

import com.example.device.core.cache.CacheKeys;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 AppKey 的固定窗口配额限流（{@code tech-design} §9.6）。
 * <p>
 * 计数放 Redis 而不是进程内：多实例才能看到同一份配额，否则扩副本等于按实例数放大配额。
 * 窗口长度固定 1 秒，键带窗口起点，因此不需要"清理旧键"的逻辑——TTL 到期自然消失。
 * </p>
 * <p>
 * <b>Redis 不可用时放行（fail-open）</b>：限流是保护手段，不是鉴权；把它做成硬依赖会让一次
 * Redis 抖动连带打挂对外查询。Redis 与 DB 同时不可用时，回源会失败并由
 * {@code DependencyFailureAdvice} 返回 {@code 503/5030}，仍不会出现"既不限流又静默成功"。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class QuotaRateLimiter {

    /** 窗口长度（秒）：与 {@code device.openapi.qps-per-app} 的"每秒请求数"语义一致。 */
    private static final long WINDOW_SECONDS = 1L;

    /** 计数键的 TTL：窗口结束后的一个完整窗口即可丢弃，不长期占内存。 */
    private static final Duration KEY_TTL = Duration.ofSeconds(2);

    private static final Logger LOG = LoggerFactory.getLogger(QuotaRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final long limitPerWindow;

    /**
     * @param redisTemplate Redis 模板
     * @param qpsPerApp     {@code device.openapi.qps-per-app}；设计文档要求"未配置时拒绝启动而非默认放行"
     */
    public QuotaRateLimiter(StringRedisTemplate redisTemplate,
                            @Value("${device.openapi.qps-per-app}") long qpsPerApp) {
        if (qpsPerApp <= 0) {
            throw new IllegalStateException(
                    "device.openapi.qps-per-app must be > 0: refusing to start without a quota");
        }
        this.redisTemplate = redisTemplate;
        this.limitPerWindow = qpsPerApp;
    }

    /**
     * 申请一次配额。
     *
     * @param appKey 调用方标识
     * @return 判定结果与三个限流头的取值
     */
    public Decision tryAcquire(String appKey) {
        long window = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long resetEpochSec = window + WINDOW_SECONDS;
        try {
            String key = CacheKeys.openapiQuota(appKey, window);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, KEY_TTL);
            }
            long used = count == null ? 1L : count;
            return new Decision(used <= limitPerWindow, limitPerWindow,
                    Math.max(0L, limitPerWindow - used), resetEpochSec);
        } catch (DataAccessException e) {
            LOG.warn("quota counter unavailable, fail-open for appKey={}", appKey, e);
            return new Decision(true, limitPerWindow, limitPerWindow, resetEpochSec);
        }
    }

    /**
     * 一次限流判定的结果。
     *
     * @param allowed       是否放行
     * @param limit         窗口内上限
     * @param remaining     剩余次数
     * @param resetEpochSec 窗口重置时间（Unix 秒）
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record Decision(boolean allowed, long limit, long remaining, long resetEpochSec) {
    }
}
