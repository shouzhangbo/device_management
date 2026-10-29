package com.example.device.core.applier;

import com.example.device.core.cache.DeviceInfoCache;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 缓存失效的事务同步注册器。
 * <p>
 * 只在 {@code afterCommit} 删缓存（{@code tech-design} R8）：事务里删缓存意味着
 * "缓存已空但库还没提交"，此刻并发读会把<b>旧值</b>回填进缓存并驻留一个 TTL 周期；
 * 提交前删（beforeCommit）同理更糟。注册动作本身不产生 Redis 调用。
 * </p>
 * <p>
 * 无活动事务时直接执行——本类同时被后台建档等非事务路径复用，静默丢弃注册会让缓存脏到对账周期才修。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class CacheInvalidationRegistry {

    private final DeviceInfoCache cache;

    /**
     * @param cache 缓存组件，失效只走它的 {@code DEL}
     */
    public CacheInvalidationRegistry(DeviceInfoCache cache) {
        this.cache = cache;
    }

    /**
     * 注册一个提交后删除动作。
     *
     * @param sn 设备序列号
     */
    public void registerSnDelete(String sn) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evict(sn);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(sn);
            }
        });
    }

    /**
     * 注册一批提交后删除动作（缓存对账用，走一次 Pipeline）。
     *
     * @param sns 设备序列号列表
     */
    public void registerSnDeleteAll(List<String> sns) {
        if (sns.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evictMulti(sns);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evictMulti(sns);
            }
        });
    }
}
