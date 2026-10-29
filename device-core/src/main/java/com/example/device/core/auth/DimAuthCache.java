package com.example.device.core.auth;

import com.example.device.core.dim.DimType;
import com.example.device.core.entity.DeviceDimSourceAuthDO;
import com.example.device.core.mapper.DeviceDimSourceAuthMapper;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 维度权威源授权的内存快照（EP-012）。
 * <p>
 * 消息热路径只读内存，<b>禁止</b>每条消息查库：那会把 DB QPS 放大到与消息量同级，
 * 而这张表满几十行、几分钟才变一次。多实例各自独立加载，允许最长一个刷新周期的授权不一致。
 * </p>
 * <p>
 * 刷新失败时保留上一份快照并 WARN——授权表读不到就让消费停摆，等于把配置库变成主链路强依赖。
 * 但<b>首次加载失败必须让进程启动失败</b>：没有快照就等于全拒绝，进程看似健康却一条都写不进。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DimAuthCache {

    private static final Logger log = LoggerFactory.getLogger(DimAuthCache.class);

    private final DeviceDimSourceAuthMapper mapper;

    /** {@code DimType → 被授权且启用的 source 集合}；volatile 保证刷新后读线程立即可见。 */
    private volatile Map<DimType, Set<String>> snapshot = Collections.emptyMap();

    /**
     * @param mapper 授权配置读取入口
     */
    public DimAuthCache(DeviceDimSourceAuthMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 启动期首次加载：失败直接抛出，让进程拒绝启动。
     */
    @jakarta.annotation.PostConstruct
    public void loadOnStartup() {
        this.snapshot = load();
        log.info("dimension authorization loaded, dims={}, entries={}", snapshot.size(), countEntries());
        if (snapshot.isEmpty()) {
            // 空表 = 全部来源被拒 = 所有消息静默丢弃，是上线 checklist 第一条要验的事（tech-design R-005）。
            log.error("dimension authorization table is EMPTY: every message will be silently rejected");
        }
    }

    /**
     * 周期全量刷新：失败时保留上一份快照。
     */
    @Scheduled(fixedDelayString = "${device.dim.auth-refresh-seconds:60}000")
    public void refresh() {
        try {
            Map<DimType, Set<String>> loaded = load();
            if (loaded.isEmpty()) {
                log.error("dimension authorization refresh returned EMPTY, keep previous snapshot");
                return;
            }
            this.snapshot = loaded;
        } catch (RuntimeException e) {
            log.warn("dimension authorization refresh failed, keep previous snapshot", e);
        }
    }

    /**
     * 当前快照。
     *
     * @return 只读的 {@code dim → sources} 映射
     */
    public Map<DimType, Set<String>> snapshot() {
        return snapshot;
    }

    /**
     * 被授权的维度数量，用于启动日志与就绪判断。
     *
     * @return 有至少一个授权来源的维度数
     */
    public int authorizedDimCount() {
        return snapshot.size();
    }

    private int countEntries() {
        int n = 0;
        for (Set<String> sources : snapshot.values()) {
            n += sources.size();
        }
        return n;
    }

    private Map<DimType, Set<String>> load() {
        List<DeviceDimSourceAuthDO> rows;
        try {
            rows = mapper.selectAllEnabled();
        } catch (RuntimeException e) {
            throw new UnauthenticatedRefreshedException(e);
        }
        Map<DimType, Set<String>> next = new EnumMap<>(DimType.class);
        for (DeviceDimSourceAuthDO row : rows) {
            DimType dim = toDimType(row.getDimType());
            if (dim == null) {
                log.warn("unknown dim_type in authorization table, ignored: {}", row.getDimType());
                continue;
            }
            next.computeIfAbsent(dim, k -> new HashSet<>()).add(row.getSource());
        }
        Map<DimType, Set<String>> frozen = new EnumMap<>(next);
        next.forEach((dim, sources) -> frozen.put(dim, Set.copyOf(sources)));
        return Collections.unmodifiableMap(frozen);
    }

    private static DimType toDimType(String code) {
        try {
            return DimType.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
