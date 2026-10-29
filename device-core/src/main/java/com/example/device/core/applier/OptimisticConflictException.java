package com.example.device.core.applier;

/**
 * 乐观锁版本冲突（CAS 影响行数为 0）。
 * <p>
 * 抛出即让事务回滚，由 {@code RetryBudget} 在<b>事务外</b>退避重试；重试会重新 SELECT 取最新版本。
 * 服务端<b>不静默重试</b>到成功为止，也不对外暴露版本号语义（{@code tech-design} R4）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class OptimisticConflictException extends RuntimeException {

    private final String sn;
    private final int expectedVersion;

    /**
     * 记录冲突现场，便于日志定位。
     *
     * @param sn              设备序列号
     * @param expectedVersion 本次 CAS 使用的版本
     */
    public OptimisticConflictException(String sn, int expectedVersion) {
        super("CAS conflict on sn=" + sn + " expectedVersion=" + expectedVersion);
        this.sn = sn;
        this.expectedVersion = expectedVersion;
    }

    /**
     * @return 冲突的设备序列号
     */
    public String sn() {
        return sn;
    }

    /**
     * @return 本次 CAS 使用的版本号
     */
    public int expectedVersion() {
        return expectedVersion;
    }
}
