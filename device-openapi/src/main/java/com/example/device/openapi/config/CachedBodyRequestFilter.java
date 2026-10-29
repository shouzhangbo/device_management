package com.example.device.openapi.config;

import com.example.device.openapi.security.SignatureAuthInterceptor.CachedBodyHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把 {@code /api/v1/**} 的请求体缓存下来，供验签与 Controller 各读一次。
 * <p>
 * Servlet 的输入流只能读一次，而签名串要求 body 参与摘要、Controller 又要反序列化同一个 body。
 * 因此这里先整体读入内存并用 {@link CachedBodyHttpServletRequest} 包装，之后两次读取互不影响。
 * </p>
 * <p>
 * 上限 {@value #MAX_BODY_BYTES} 字节：合法批量请求最多 100 个 SN（约数 KB），超出该上限的请求
 * 一定是异常输入；超限时不再缓存并打标记，由拦截器在验签阶段拒绝（{@code 400/4002}），
 * 避免把"未鉴权的超大请求"读进内存。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public class CachedBodyRequestFilter extends OncePerRequestFilter {

    /** 允许缓存的请求体上限（含）。 */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isSignedPath(request)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            request.setAttribute(CachedBodyHolder.TOO_LARGE_ATTRIBUTE, Boolean.TRUE);
            chain.doFilter(new CachedBodyHttpServletRequest(request, new byte[0]), response);
            return;
        }
        request.setAttribute(CachedBodyHolder.BODY_ATTRIBUTE, body);
        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private static boolean isSignedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/v1/");
    }
}
