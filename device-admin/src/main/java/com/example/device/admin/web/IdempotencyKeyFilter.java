package com.example.device.admin.web;

import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 建档接口的 {@code Idempotency-Key} 强制校验（HTTP 规范 §9.1【强制】）。
 * <p>
 * 缺失 → {@code 400/4001}；格式不合法（非 {@code [A-Za-z0-9_-]{8,64}}）→ {@code 400/4002}。
 * 只拦 {@code POST /api/v1/admin/devices} 一个入口：编辑与删除的幂等语义由 {@code version} 承担，
 * 死信重放/丢弃由状态机承担，给它们再套一层幂等键只会让运维重试变复杂。
 * </p>
 * <p>
 * 本类在 Spring Security 过滤链之后执行，因此未认证请求先得到 {@code 401/4011}，
 * 不会出现"未登录却被告知缺 Idempotency-Key"的错误引导。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    /** 请求头名。 */
    public static final String HEADER = "Idempotency-Key";

    /** 契约 {@code IdempotencyKey} 的字符集与长度。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private static final String CREATE_PATH = "/api/v1/admin/devices";

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 与响应同一个映射器，保证错误外壳的字段命名一致
     */
    public IdempotencyKeyFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isCreateRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            reject(response, ErrorCode.PARAM_MISSING, "required header missing: " + HEADER);
            return;
        }
        if (!KEY_PATTERN.matcher(key.trim()).matches()) {
            reject(response, ErrorCode.PARAM_FORMAT_ERROR,
                    HEADER + " must match ^[A-Za-z0-9_-]{8,64}$");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isCreateRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && CREATE_PATH.equals(request.getRequestURI());
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiEnvelope.error(code, message));
    }
}
