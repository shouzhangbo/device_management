package com.example.device.core.applier;

/**
 * 事务内的处理结果。
 * <p>
 * 只有三种：写侧的"未授权 / 未知类型"在<b>事务之前</b>就被拦掉，重复消息由
 * {@code DuplicateKeyException} 在事务<b>之外</b>转成终态（R2：在事务内 catch 会让事务进 rollback-only）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum ApplyOutcome {

    /** 维度列已更新（含建档），流水 {@code result=1}。 */
    APPLIED,

    /** 设备不存在且不建档：流水 {@code result=3}，事务<b>正常提交</b>并 ack（R5）。 */
    MISSING_DEVICE_RECORDED,

    /** DB 唯一索引判定为重复消息，事务未启动或已回滚，不落流水。 */
    DUPLICATE;

    /**
     * 是否需要在提交后删缓存。
     *
     * @return true 表示主档已变化
     */
    public boolean shouldEvictCache() {
        return this == APPLIED;
    }
}
