package com.example.device.core.contract;

/**
 * 信封语义校验结果。
 *
 * @param status  状态
 * @param command 通过时的命令，拒绝时为 {@code null}
 * @param reason  拒绝原因摘要，通过时为 {@code null}
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ValidateResult(ValidateStatus status, ValidatedCommand command, String reason) {

    /**
     * 是否通过校验。
     *
     * @return true 表示可以写库
     */
    public boolean isAccepted() {
        return status == ValidateStatus.OK;
    }

    /**
     * 通过。
     *
     * @param command 命令
     * @return 结果
     */
    public static ValidateResult ok(ValidatedCommand command) {
        return new ValidateResult(ValidateStatus.OK, command, null);
    }

    /**
     * 载荷非法。
     *
     * @param reason 原因
     * @return 结果
     */
    public static ValidateResult invalid(String reason) {
        return new ValidateResult(ValidateStatus.INVALID_PAYLOAD, null,
                PayloadLimits.truncateFailReason(reason));
    }

    /**
     * 未知消息类型。
     *
     * @param msgType 原始值
     * @return 结果
     */
    public static ValidateResult unknownType(String msgType) {
        return new ValidateResult(ValidateStatus.UNKNOWN_MSG_TYPE, null,
                "unknown msg_type: " + msgType);
    }
}
