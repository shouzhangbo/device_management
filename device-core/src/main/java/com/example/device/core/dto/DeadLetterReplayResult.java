package com.example.device.core.dto;

/**
 * 死信重放的落地结果（契约 {@code replayDeadLetter} 的 {@code data}）。
 * <p>
 * {@code DEVICE_NOT_FOUND} 与 {@code SUCCESS} 都算"重放动作已完成"——留痕即目的，
 * 不代表要再重试；只有幂等冲突与状态机冲突才用错误码表达（{@code 4092} / {@code 4093}）。
 * </p>
 *
 * @param id           死信记录主键
 * @param status       处理后的状态，恒为 1（已重放）
 * @param applyResult  {@code SUCCESS} 或 {@code DEVICE_NOT_FOUND}
 * @param recordResult 落流水的 {@code result} 值（1 或 3）
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeadLetterReplayResult(Long id, Integer status, String applyResult, Integer recordResult) {

    /** 主档已更新。 */
    public static final String APPLIED = "SUCCESS";

    /** 设备不存在且不建档：流水留痕 {@code result=3}。 */
    public static final String DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";
}
