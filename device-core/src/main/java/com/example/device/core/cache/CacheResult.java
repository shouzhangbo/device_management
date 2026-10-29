package com.example.device.core.cache;

/**
 * 一次缓存读取的结局。
 * <p>
 * {@link #DEGRADED} 与 {@link #MISS} 必须区分：前者表示 Redis 不可用，读侧要<b>直查数据库且不回填</b>
 * （{@code tech-design} §11.1"超时后的行为"），后者才走重建锁回源。把两者混成一个分支，
 * Redis 抖动时所有请求会同时抢重建锁并集中回填，反而放大故障。
 * </p>
 *
 * @param status 结局类型
 * @param value  {@link #HIT} 时的值，其余为 {@code null}
 * @author houzhangbo
 * @since 2026-10-29
 */
public record CacheResult(Status status, DeviceCacheValue value) {

    /** 缓存结局。 */
    public enum Status {
        /** 命中真实值。 */
        HIT,
        /** 命中空值哨兵：确认设备不存在，<b>不回源</b>。 */
        EMPTY_SENTINEL,
        /** 未命中：需要回源并回填。 */
        MISS,
        /** Redis 异常或值不可解析：直查数据库且不回填。 */
        DEGRADED
    }

    /**
     * 是否可直接返回（命中或确认不存在）。
     *
     * @return true 表示不需要回源
     */
    public boolean isTerminal() {
        return status == Status.HIT || status == Status.EMPTY_SENTINEL;
    }

    /**
     * 是否命中哨兵。
     *
     * @return true 表示设备不存在或已逻辑删除
     */
    public boolean isEmptySentinel() {
        return status == Status.EMPTY_SENTINEL;
    }
}
