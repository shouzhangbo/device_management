package com.example.device.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路 ID 过滤器：沿用或生成 {@code trace_id} → 写 MDC → 写响应头 → 请求结束清理。
 * <p>
 * 必须继承 {@code OncePerRequestFilter}（Spring Boot 规范【强制】），否则异步/forward 场景会重复进入，
 * 造成同一请求多个 traceId 或 MDC 被提前清掉。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.sanitize(request.getHeader(TraceContext.HEADER));
        if (traceId == null) {
            traceId = TraceContext.generate();
        }
        MDC.put(TraceContext.MDC_KEY, traceId);
        try {
            // 头必须在业务写出之前落定，否则错误响应已经 flush 就无法再补 trace_id 头。
            response.setHeader(TraceContext.HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
