package com.example.device.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：把异常统一翻译成"精确 HTTP status + 业务 code + {@code trace_id}"的外壳。
 * <p>
 * 本仓库<b>不启用</b> {@code spring.mvc.problemdetails.enabled}：ProblemDetail 与统一外壳是两套契约，
 * 同时输出会让调用方无法解析。该冲突已在 {@code tech-design} 附录 C 登记并以 §9.6 为准。
 * </p>
 * <p>
 * 状态码口径是"HTTP status 表传输层 + {@code code} 表业务层"，不是"恒 HTTP 200 + 业务 code"；
 * 因此 409 类冲突绝不压成 400，失败绝不用 200 表达。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务拒绝：按错误码自带的 HTTP status 返回，消息允许携带可操作提示但不含内部细节。
     *
     * @param e 业务异常
     * @return 错误外壳
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiEnvelope<Void>> onBiz(BizException e) {
        ErrorCode code = e.errorCode();
        log.info("business rejected, code={}, message={}", code.code(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ApiEnvelope.error(code, e.getMessage()));
    }

    /**
     * Bean Validation 失败 → {@code 400/4002}，取第一条字段错误便于调用方定位。
     * <p>
     * 只拼字段名、不拼 {@code getDefaultMessage()}：后者随 JVM 默认语言变化（中文环境下是中文），
     * 而契约 {@code Envelope.message} 要求"稳定英文短语，供调用方 i18n 映射"。
     * </p>
     *
     * @param e 校验异常
     * @return 错误外壳
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiEnvelope<Void>> onInvalid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String detail = errors.isEmpty()
                ? ErrorCode.PARAM_FORMAT_ERROR.message()
                : ErrorCode.PARAM_FORMAT_ERROR.message() + ": " + errors.get(0).getField();
        return build(ErrorCode.PARAM_FORMAT_ERROR, detail);
    }

    /**
     * 请求体不可解析（非法 JSON、类型不匹配）→ {@code 400/4002}。
     *
     * @param e 解析异常
     * @return 错误外壳
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiEnvelope<Void>> onUnreadable(HttpMessageNotReadableException e) {
        log.info("request body not readable: {}", e.getMessage());
        return build(ErrorCode.PARAM_FORMAT_ERROR, ErrorCode.PARAM_FORMAT_ERROR.message());
    }

    /**
     * 必填请求头缺失 → {@code 400/4001}（如建档缺 {@code Idempotency-Key}）。
     *
     * @param e 缺头异常
     * @return 错误外壳
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiEnvelope<Void>> onMissingHeader(MissingRequestHeaderException e) {
        return build(ErrorCode.PARAM_MISSING, "required header missing: " + e.getHeaderName());
    }

    /**
     * 必填查询参数缺失 → {@code 400/4001}（如逻辑删缺 {@code version}）。
     * <p>
     * 不处理它就会落到兜底分支变成 {@code 500/5000}，把调用方的入参错误报成服务端故障。
     * </p>
     *
     * @param e 缺参异常
     * @return 错误外壳
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiEnvelope<Void>> onMissingParameter(MissingServletRequestParameterException e) {
        return build(ErrorCode.PARAM_MISSING, "required parameter missing: " + e.getParameterName());
    }

    /**
     * 查询参数类型不匹配 → {@code 400/4002}。
     *
     * @param e 类型不匹配异常
     * @return 错误外壳
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiEnvelope<Void>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(ErrorCode.PARAM_FORMAT_ERROR, "parameter format error: " + e.getName());
    }

    /**
     * 方法不允许 → {@code 405/4050}（框架异常直接映射，不新增码）。
     *
     * @param e 方法不支持异常
     * @return 错误外壳
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Void>> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.message());
    }

    /**
     * 兜底：ERROR 打完整堆栈，响应体<b>不回显</b>内部异常信息（会泄漏 SQL 片段与类名）。
     *
     * @param e         未捕获异常
     * @param request   当前请求，用于在日志里定位入口
     * @return 错误外壳
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Void>> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled exception, uri={}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message()));
    }

    private ResponseEntity<ApiEnvelope<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiEnvelope.error(code, message));
    }
}
