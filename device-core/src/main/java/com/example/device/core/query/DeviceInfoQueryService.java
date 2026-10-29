package com.example.device.core.query;

import com.example.device.core.cache.CacheResult;
import com.example.device.core.cache.CacheProperties;
import com.example.device.core.cache.DeviceCacheValue;
import com.example.device.core.cache.DeviceInfoCache;
import com.example.device.core.dto.DevicePublicView;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.mapper.DeviceInfoMapper;
import com.example.device.core.metrics.DeviceIngestMetrics;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对外读路径（EP-001 / EP-002）：缓存优先 → 回源 → 装配。
 * <p>
 * <b>本类永不写库、永不删缓存</b>（{@code tech-design} §9.2）。逻辑删除与"从未存在"在这里合并成
 * 同一个 {@link Optional#empty()}，由 Controller 映射 {@code 404/4040}——对外区分两者会泄露内部状态
 * （§9.5），两者同样写 {@code __EMPTY__} 哨兵。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Service
public class DeviceInfoQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceInfoQueryService.class);

    /** 自旋等待重建方回填的时间片；总等待上界 = 次数 × 该值，量级远小于一次回源。 */
    private static final long SPIN_WAIT_MILLIS = 20L;

    private final DeviceInfoCache cache;
    private final DeviceInfoMapper mapper;
    private final PublicAssembler assembler;
    private final CacheProperties cacheProperties;
    private final QueryProperties queryProperties;
    private final DeviceIngestMetrics metrics;

    /**
     * @param cache           缓存唯一出入口
     * @param mapper          主档只读方法
     * @param assembler       可见列唯一装配点
     * @param cacheProperties TTL 与自旋次数
     * @param queryProperties 批量上限
     * @param metrics         缓存命中计数
     */
    public DeviceInfoQueryService(DeviceInfoCache cache, DeviceInfoMapper mapper, PublicAssembler assembler,
                                  CacheProperties cacheProperties, QueryProperties queryProperties,
                                  DeviceIngestMetrics metrics) {
        this.cache = cache;
        this.mapper = mapper;
        this.assembler = assembler;
        this.cacheProperties = cacheProperties;
        this.queryProperties = queryProperties;
        this.metrics = metrics;
    }

    /**
     * 单查（EP-001）。
     *
     * @param sn 设备序列号
     * @return 对外视图；不存在或已逻辑删除时为 empty
     */
    public Optional<DevicePublicView> findBySn(String sn) {
        CacheResult result = cache.get(sn);
        switch (result.status()) {
            case HIT -> {
                metrics.cacheLookup("hit");
                return Optional.of(assembler.fromCache(result.value()));
            }
            case EMPTY_SENTINEL -> {
                metrics.cacheLookup("empty_sentinel");
                return Optional.empty();
            }
            case DEGRADED -> {
                metrics.cacheLookup("degraded");
                return Optional.ofNullable(queryWithoutBackfill(sn));
            }
            default -> {
                metrics.cacheLookup("miss");
                return rebuildAndReturn(sn);
            }
        }
    }

    /**
     * 批量查（EP-002）：一次 MGET + 一次 {@code IN} + 一次 Pipeline 回填。
     * <p>
     * 超限<b>整体拒绝</b>（{@code 400/4003}），不拆分、不去重后静默减量。
     * 本方法不打乱顺序：返回条目与请求 SN 顺序一致。
     * </p>
     *
     * @param sns 请求的设备序列号，允许重复
     * @return 逐项结果与缺失摘要
     * @throws BizException 空列表或超过 {@code device.query.batch-max}
     */
    public BatchQueryResult findBatch(List<String> sns) {
        if (sns == null || sns.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "sns must not be empty");
        }
        int limit = queryProperties.batchMaxOrDefault();
        if (sns.size() > limit) {
            LOG.warn("batch query rejected: size={} limit={}", sns.size(), limit);
            throw new BizException(ErrorCode.BATCH_LIMIT_EXCEEDED,
                    "sns size exceeds device.query.batch-max=" + limit);
        }
        List<String> distinct = List.copyOf(new LinkedHashSet<>(sns));
        Map<String, CacheResult> cached = cache.getMulti(distinct);
        Map<String, DevicePublicView> resolved = new LinkedHashMap<>(distinct.size());
        List<String> toRebuild = new ArrayList<>();
        List<String> degradedSns = new ArrayList<>();
        for (String sn : distinct) {
            CacheResult r = cached.get(sn);
            if (r == null || r.status() == CacheResult.Status.MISS) {
                metrics.cacheLookup("miss");
                toRebuild.add(sn);
            } else if (r.status() == CacheResult.Status.HIT) {
                metrics.cacheLookup("hit");
                resolved.put(sn, assembler.fromCache(r.value()));
            } else if (r.status() == CacheResult.Status.EMPTY_SENTINEL) {
                metrics.cacheLookup("empty_sentinel");
            } else {
                metrics.cacheLookup("degraded");
                degradedSns.add(sn);
            }
        }
        if (!toRebuild.isEmpty()) {
            loadBatch(toRebuild, resolved, true);
        }
        if (!degradedSns.isEmpty()) {
            loadBatch(degradedSns, resolved, false);
        }
        List<BatchQueryResult.Item> items = new ArrayList<>(sns.size());
        for (String sn : sns) {
            DevicePublicView view = resolved.get(sn);
            items.add(new BatchQueryResult.Item(sn, view != null, view));
        }
        return BatchQueryResult.of(items);
    }

    /**
     * 抢重建锁并回源；抢不到时有界自旋，等不到就直查数据库<b>且不回填</b>。
     */
    private Optional<DevicePublicView> rebuildAndReturn(String sn) {
        if (!cache.tryRebuildLock(sn)) {
            for (int i = 0, times = cacheProperties.spinTimesOrDefault(); i < times; i++) {
                if (!sleepBriefly()) {
                    break;
                }
                CacheResult again = cache.get(sn);
                if (again.status() == CacheResult.Status.HIT) {
                    return Optional.of(assembler.fromCache(again.value()));
                }
                if (again.status() == CacheResult.Status.EMPTY_SENTINEL) {
                    return Optional.empty();
                }
            }
            metrics.cacheLookup("rebuild_lock_busy_direct_query");
            return Optional.ofNullable(queryWithoutBackfill(sn));
        }
        try {
            DeviceInfoDO row = mapper.selectBySnVisible(sn);
            if (row == null) {
                cache.putEmpty(sn);
                return Optional.empty();
            }
            DeviceCacheValue value = assembler.toCacheValue(row);
            cache.put(sn, value);
            return Optional.of(assembler.fromCache(value));
        } finally {
            cache.releaseRebuildLock(sn);
        }
    }

    /**
     * 批量回源：一次 {@code IN}；{@code backfill} 为真时命中回填、缺失写哨兵。
     * <p>
     * 降级分片（Redis 不可用）必须传 {@code false}：恢复瞬间集中回填会把故障放大成雪崩。
     * </p>
     */
    private void loadBatch(List<String> sns, Map<String, DevicePublicView> resolved, boolean backfill) {
        List<DeviceInfoDO> rows = mapper.selectBySnList(sns);
        Map<String, DeviceCacheValue> values = new LinkedHashMap<>();
        for (DeviceInfoDO row : rows) {
            DeviceCacheValue value = assembler.toCacheValue(row);
            values.put(row.getSn(), value);
            resolved.put(row.getSn(), assembler.fromCache(value));
        }
        if (!backfill) {
            return;
        }
        cache.putMulti(values);
        sns.stream().filter(sn -> !values.containsKey(sn)).forEach(cache::putEmpty);
    }

    /**
     * 降级直查：只读数据库，<b>不回填</b>。
     * <p>
     * Redis 不可用时若照常回填，恢复瞬间会有一大批请求同时抢重建锁并集中写入，把故障放大成雪崩。
     * </p>
     */
    private DevicePublicView queryWithoutBackfill(String sn) {
        DeviceInfoDO row = mapper.selectBySnVisible(sn);
        return row == null ? null : assembler.fromCache(assembler.toCacheValue(row));
    }

    private static boolean sleepBriefly() {
        try {
            Thread.sleep(SPIN_WAIT_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
