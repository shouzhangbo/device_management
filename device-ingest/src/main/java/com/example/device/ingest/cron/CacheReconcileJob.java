package com.example.device.ingest.cron;

import com.example.device.core.cache.CacheProperties;
import com.example.device.core.cache.CacheResult;
import com.example.device.core.cache.DeviceInfoCache;
import com.example.device.core.config.CronProperties;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.mapper.DeviceInfoMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EP-007 缓存对账：把"写库成功但没删到缓存"的脏数据补齐。
 * <p>
 * 修复动作<b>只有 {@code DEL}</b>，绝不回写新值——回写会与并发的重建路径竞争，可能把更旧的值写回缓存
 * （{@code tech-design} §5.2 EP-007）。
 * </p>
 * <p>
 * 三条硬约束：<br>
 * ① 只用 {@code idx_update_time} 增量扫，单批 ≤ {@code device.cache.reconcile.batch-size}，禁止全表；<br>
 * ② 比对用一次 {@code MGET}，删除用一次 Pipeline {@code DEL}，禁止逐条 GET；<br>
 * ③ 单轮时长受锁 TTL 约束，剩余锁时长低于 {@code TTL × 0.2} 主动截断，下轮从已推进的水位续跑。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class CacheReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CacheReconcileJob.class);

    /** 任务名，同时是 cron 锁的第二段。 */
    public static final String JOB = "cache-reconcile";

    /** 周期无法从 cron 表达式推算时的兜底（= §5.2 建议的 5 分钟），不是新增配置键。 */
    private static final Duration FALLBACK_PERIOD = Duration.ofMinutes(5);

    private final DeviceInfoMapper deviceInfoMapper;
    private final DeviceInfoCache cache;
    private final CronLock cronLock;
    private final ReconcileWatermark watermarkStore;
    private final CacheProperties cacheProperties;
    private final CronProperties cronProperties;

    /**
     * @param deviceInfoMapper 主档持久层
     * @param cache            缓存读写
     * @param cronLock         跨实例互斥
     * @param watermarkStore   水位
     * @param cacheProperties  批量大小与 cron
     * @param cronProperties   锁比例参数
     */
    public CacheReconcileJob(DeviceInfoMapper deviceInfoMapper, DeviceInfoCache cache, CronLock cronLock,
                             ReconcileWatermark watermarkStore, CacheProperties cacheProperties,
                             CronProperties cronProperties) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.cache = cache;
        this.cronLock = cronLock;
        this.watermarkStore = watermarkStore;
        this.cacheProperties = cacheProperties;
        this.cronProperties = cronProperties;
    }

    /**
     * 一轮对账。抢不到锁直接返回（别的实例在跑）。
     */
    @Scheduled(cron = "${device.cache.reconcile.cron:0 */5 * * * ?}")
    public void reconcile() {
        Duration period = period();
        Duration lockTtl = CronSchedule.lockTtl(period, cronProperties.lockTtlRatioOrDefault());
        if (!cronLock.tryLock(JOB, lockTtl)) {
            log.debug("cache reconcile skipped, another instance holds the lock");
            return;
        }
        try {
            runRound(period, lockTtl);
        } catch (RuntimeException e) {
            log.error("cache reconcile round failed, watermark kept, next round continues", e);
        } finally {
            cronLock.unlock(JOB);
        }
    }

    private void runRound(Duration period, Duration lockTtl) {
        int batchSize = cacheProperties.reconcile() == null
                ? CacheProperties.Reconcile.DEFAULT_BATCH_SIZE
                : cacheProperties.reconcile().batchSizeOrDefault();
        LocalDateTime watermark = watermarkStore.load(period);
        long scanned = 0L;
        long evicted = 0L;
        while (true) {
            List<DeviceInfoDO> rows = deviceInfoMapper.selectUpdatedAfter(watermark, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            List<String> sns = rows.stream().map(DeviceInfoDO::getSn).toList();
            Map<String, CacheResult> cached = cache.getMulti(sns);
            if (cached.values().stream().anyMatch(r -> r.status() == CacheResult.Status.DEGRADED)) {
                // Redis 不可用时本轮放弃并保留水位：既 DEL 不了，也无法判断一致性（规则 6）。
                log.warn("cache reconcile abandoned, redis degraded, scanned={}", scanned);
                return;
            }
            List<String> stale = new ArrayList<>();
            for (DeviceInfoDO row : rows) {
                if (isStale(row, cached.get(row.getSn()))) {
                    stale.add(row.getSn());
                }
            }
            if (!stale.isEmpty()) {
                cache.evictMulti(stale);
                evicted += stale.size();
            }
            scanned += rows.size();
            watermark = rows.get(rows.size() - 1).getUpdateTime();
            watermarkStore.save(watermark);
            if (rows.size() < batchSize) {
                break;
            }
            if (truncated(lockTtl)) {
                log.warn("cache reconcile truncated, lock remaining below safety margin, scanned={}, evicted={}",
                        scanned, evicted);
                break;
            }
        }
        log.info("cache reconcile round done, scanned={}, evicted={}, watermark={}", scanned, evicted, watermark);
    }

    /**
     * 缓存是否落后于主档。
     * <p>
     * 已删除的行只有"缓存仍是真实值"才算脏：哨兵本身就是"设备不存在"的正确表达，
     * 因此 {@code EMPTY_SENTINEL} 只在行可见时才需要删。
     * </p>
     */
    private boolean isStale(DeviceInfoDO row, CacheResult cached) {
        if (cached == null || cached.status() == CacheResult.Status.MISS
                || cached.status() == CacheResult.Status.DEGRADED) {
            return false;
        }
        if (cached.status() == CacheResult.Status.EMPTY_SENTINEL) {
            return row.getDeleted() == null || row.getDeleted() == 0;
        }
        LocalDateTime cachedAt = cached.value() == null ? null : cached.value().updateTime();
        return cachedAt == null || cachedAt.isBefore(row.getUpdateTime());
    }

    private boolean truncated(Duration lockTtl) {
        long margin = (long) (lockTtl.toMillis() * cronProperties.safetyMarginRatioOrDefault());
        return cronLock.remainingMillis(JOB) < margin;
    }

    private Duration period() {
        String cron = cacheProperties.reconcile() == null ? null : cacheProperties.reconcile().cron();
        return CronSchedule.period(cron, FALLBACK_PERIOD);
    }
}
