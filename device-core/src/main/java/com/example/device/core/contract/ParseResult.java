package com.example.device.core.contract;

/**
 * 信封解析结果。
 * <p>
 * 只有<b>解析失败</b>才会落死信（R12）；语义非法（未知 {@code msg_type}、白名单越界）由
 * {@link EnvelopeValidator} 判定，走"计数 + WARN + ack"，不落死信（R11）。
 * </p>
 *
 * @param status  解析状态
 * @param envelope 解析成功时的信封，失败时为 {@code null}
 * @param reason  失败原因摘要，成功时为 {@code null}
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ParseResult(Status status, MessageEnvelope envelope, String reason) {

    /** 解析状态。 */
    public enum Status {
        /** 解析成功。 */
        OK,
        /** 空载荷。 */
        EMPTY_PAYLOAD,
        /** 超过 {@code device.ingest.max-payload-bytes}。 */
        OVERSIZE,
        /** 不是合法 JSON。 */
        NOT_JSON,
        /** JSON 根不是对象。 */
        NOT_OBJECT,
        /** 缺必填字段或字段类型不对。 */
        MALFORMED
    }

    /**
     * 构造成功结果。
     *
     * @param envelope 信封
     * @return 结果
     */
    public static ParseResult ok(MessageEnvelope envelope) {
        return new ParseResult(Status.OK, envelope, null);
    }

    /**
     * 构造失败结果。
     *
     * @param status 失败状态
     * @param reason 原因摘要
     * @return 结果
     */
    public static ParseResult fail(Status status, String reason) {
        return new ParseResult(status, null, PayloadLimits.truncateFailReason(status + ": " + reason));
    }

    /**
     * 是否解析失败（失败即落死信）。
     *
     * @return true 表示需要落死信
     */
    public boolean isBad() {
        return status != Status.OK;
    }
}
