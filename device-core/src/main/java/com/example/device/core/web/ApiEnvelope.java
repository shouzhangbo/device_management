package com.example.device.core.web;

/**
 * 统一响应外壳：契约 {@code components.schemas.Envelope} 的四个字段。
 * <p>
 * {@code traceId} 经全局 snake_case 命名策略输出为 {@code trace_id}，因此本类不写 {@code @JsonProperty}
 * ——命名策略在 {@code JacksonConfig} 里声明一次即可，散落注解会让"字段集"出现两处真源。
 * </p>
 * <p>
 * 本外壳的<b>唯一</b>例外是 EP-009 的 204：无响应体，所以不存在"未包装的 JSON"。
 * 禁止为了凑统一外壳把 204 改成 {@code 200 + {code:0}}。
 * </p>
 *
 * @param code    业务码，成功恒为 0
 * @param message 稳定英文短语
 * @param data    业务数据，错误时恒为 {@code null}
 * @param traceId 链路 ID，与响应头 {@code X-Trace-Id} 恒等
 * @param <T>     业务数据类型
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ApiEnvelope<T>(int code, String message, T data, String traceId) {

    /** 成功消息，与契约示例同串。 */
    public static final String SUCCESS_MESSAGE = "success";

    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 外壳
     */
    public static <T> ApiEnvelope<T> success(T data) {
        return new ApiEnvelope<>(ErrorCode.SUCCESS_CODE, SUCCESS_MESSAGE, data, TraceContext.current());
    }

    /**
     * 错误响应：{@code data} 恒为 {@code null}（契约 {@code Error} 的 {@code data: 'null'}）。
     *
     * @param errorCode 业务错误码
     * @param message   面向调用方的消息
     * @param <T>       业务数据类型（仅为泛型兼容，实际恒为 null）
     * @return 外壳
     */
    public static <T> ApiEnvelope<T> error(ErrorCode errorCode, String message) {
        return new ApiEnvelope<>(errorCode.code(), message, null, TraceContext.current());
    }

    /**
     * 以错误码默认消息构造错误响应。
     *
     * @param errorCode 业务错误码
     * @param <T>       业务数据类型
     * @return 外壳
     */
    public static <T> ApiEnvelope<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.message());
    }
}
