package com.example.device.core.web;

/**
 * 业务错误码：与 {@code tech-design} §9.6 表、契约 {@code Error.code} enum 逐项同串，共 17 项。
 * <p>
 * 编号规则是"前 3 位对齐 HTTP status，末位为业务细分"（{@code status × 10 + 序号}），
 * 因此 {@link #status()} 可由 {@code code / 10} 反推；
 * 本类仍显式持有 status，是为了让"code 与 HTTP status 不一致"这类改错在编译期而非运行期暴露。
 * 每个码必须有至少一条可达触发路径（TC-API-04），禁止登记了却代码不可达。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum ErrorCode {

    /** 参数缺失。 */
    PARAM_MISSING(4001, 400, "parameter missing"),

    /** 参数格式错误（含深翻页越界）。 */
    PARAM_FORMAT_ERROR(4002, 400, "parameter format error"),

    /** 批量条数超上限。 */
    BATCH_LIMIT_EXCEEDED(4003, 400, "batch size exceeds limit"),

    /** 缺少必要筛选条件。 */
    MISSING_FILTER_CONDITION(4004, 400, "a discriminating filter condition is required"),

    /** 验签失败。 */
    SIGN_INVALID(4010, 401, "signature verification failed"),

    /** 时间戳超窗；后台场景表示未登录或 JWT 失效。 */
    TIMESTAMP_OUT_OF_WINDOW(4011, 401, "timestamp out of window or not authenticated"),

    /** 无权限。 */
    FORBIDDEN(4030, 403, "forbidden"),

    /** 该维度未授权本来源写入。 */
    DIM_NOT_AUTHORIZED(4031, 403, "this dimension is not authorized for the source"),

    /** 资源不存在（设备不存在或已逻辑删除；死信记录不存在）。 */
    RESOURCE_NOT_FOUND(4040, 404, "resource not found"),

    /** 方法不允许。 */
    METHOD_NOT_ALLOWED(4050, 405, "method not allowed"),

    /** SN 已存在。 */
    SN_ALREADY_EXISTS(4090, 409, "device sn already exists"),

    /** 版本冲突，需刷新后重试。 */
    VERSION_CONFLICT(4091, 409, "data updated externally, please refresh and retry"),

    /** 死信重放遇幂等冲突：该 {@code msg_id} 已消费过。 */
    REPLAY_HIT_IDEMPOTENT(4092, 409, "replay hit idempotent record, message already consumed"),

    /** 死信已处理，不可重放。 */
    DEAD_LETTER_ALREADY_HANDLED(4093, 409, "dead letter already handled"),

    /** 超配额限流。 */
    RATE_LIMITED(4290, 429, "quota exceeded"),

    /** 内部错误。 */
    INTERNAL_ERROR(5000, 500, "internal error"),

    /** 依赖调用超时或不可用且降级仍失败（本期由"超时 + fail-fast"产生，无熔断组件）。 */
    DEPENDENCY_UNAVAILABLE(5030, 503, "dependency unavailable");

    /** 成功码：外壳 {@code code} 恒为 0（§9.6）。 */
    public static final int SUCCESS_CODE = 0;

    /** 契约与 §9.6 登记的错误码总数。 */
    public static final int REGISTERED_COUNT = 17;

    private final int code;
    private final int status;
    private final String message;

    ErrorCode(int code, int status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    /**
     * 业务码。
     *
     * @return 形如 4091 的业务码
     */
    public int code() {
        return code;
    }

    /**
     * 传输层 HTTP 状态码。
     *
     * @return HTTP status
     */
    public int status() {
        return status;
    }

    /**
     * 稳定英文短语，供调用方 i18n 映射；不得携带具体 ID 等上下文（HTTP 规范 §5.2）。
     *
     * @return 默认消息
     */
    public String message() {
        return message;
    }
}
