package com.example.device.core.contract;

/**
 * 消息与入参的硬边界常量。
 * <p>
 * 与 {@code api-contract.yaml#x-kafka-message-contracts} 及 {@code data-model} §6 的列长度同串；
 * 改动必须三处一起改，否则会出现"契约允许但实现拒绝"或反向的漂移。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class PayloadLimits {

    /** {@code source} 与 {@code msg_id} 的最大长度（列宽 VARCHAR(64)）。 */
    public static final int MAX_ID_LENGTH = 64;

    /** SN 最大长度（列宽 VARCHAR(64)）。 */
    public static final int MAX_SN_LENGTH = 64;

    /** SN 合法字符集，与契约 {@code pattern: ^[A-Za-z0-9_-]+$} 同串。 */
    public static final String SN_PATTERN = "^[A-Za-z0-9_-]+$";

    /** 失败原因摘要落库前的截断长度（列宽 VARCHAR(1000)）。 */
    public static final int MAX_FAIL_REASON_LENGTH = 1000;

    /** 后台编辑 {@code remark} 最大长度。 */
    public static final int MAX_REMARK_LENGTH = 512;

    /** 后台编辑 {@code image_url} 最大长度。 */
    public static final int MAX_IMAGE_URL_LENGTH = 512;

    /** 型号编码最大长度。 */
    public static final int MAX_MODEL_CODE_LENGTH = 64;

    /** 型号名称最大长度。 */
    public static final int MAX_MODEL_NAME_LENGTH = 128;

    private PayloadLimits() {
    }

    /**
     * 把失败原因截断到列宽以内，避免"记录失败反而插入失败"。
     *
     * @param text 原始文本，允许为 {@code null}
     * @return 截断后的文本，永不为 {@code null}
     */
    public static String truncateFailReason(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= MAX_FAIL_REASON_LENGTH ? text : text.substring(0, MAX_FAIL_REASON_LENGTH);
    }
}
