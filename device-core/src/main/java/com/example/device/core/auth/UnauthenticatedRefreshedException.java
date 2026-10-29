package com.example.device.core.auth;

/**
 * 授权表读取失败。
 * <p>
 * 只由周期刷新抛出并被 {@link DimAuthCache#refresh()} 吞掉（保留旧快照 + WARN），
 * 启动期首载不捕获它，让进程直接失败——没有快照等于全拒绝，静默运行比不运行更糟。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class UnauthenticatedRefreshedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param cause 底层数据访问异常
     */
    public UnauthenticatedRefreshedException(Throwable cause) {
        super("dimension authorization table could not be read", cause);
    }
}
