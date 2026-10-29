package com.example.device.core.contract;

/**
 * 信封语义校验的状态。
 * <p>
 * 与 {@link ParseResult.Status} 分开建模是硬要求：{@code MALFORMED}/{@code OVERSIZE} 落死信，
 * 本枚举的三种状态<b>都不落死信</b>（{@code tech-design} R11 / R12、{@code data-model} §8.4 末行）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum ValidateStatus {

    /** 通过，可进入写路径。 */
    OK,

    /** 载荷非法：白名单越界、类型不符、值域越界、超长。计数 + WARN + ack。 */
    INVALID_PAYLOAD,

    /** 未知 {@code msg_type}。计数 + WARN + ack，不落死信以免卡分区。 */
    UNKNOWN_MSG_TYPE
}
