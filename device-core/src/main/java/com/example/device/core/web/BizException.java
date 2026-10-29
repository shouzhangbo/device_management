package com.example.device.core.web;

/**
 * 业务拒绝的统一异常。
 * <p>
 * 消费路径不用它（消息侧靠终态枚举决定 ack 还是重试，抛异常会触发无限重投）；
 * 它只服务 HTTP 侧：由 {@code GlobalExceptionHandler} 映射成"精确 HTTP status + 业务 code"。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * 以错误码的默认消息构造。
     *
     * @param errorCode 业务错误码
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /**
     * 覆盖默认消息（仅在消息需要携带可操作提示时使用，禁止塞入内部异常信息）。
     *
     * @param errorCode 业务错误码
     * @param message   面向调用方的消息
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 业务错误码。
     *
     * @return 决定 HTTP status 与响应体 {@code code} 的枚举
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
