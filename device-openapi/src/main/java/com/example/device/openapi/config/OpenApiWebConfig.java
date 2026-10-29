package com.example.device.openapi.config;

import com.example.device.core.web.TraceIdFilter;
import com.example.device.openapi.security.SignatureAuthInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配：链路 ID 过滤器、请求体缓存过滤器与验签拦截器。
 * <p>
 * 顺序是硬要求：{@code TraceIdFilter} 必须最先执行，否则验签失败的 401 响应拿不到 {@code trace_id}；
 * 请求体缓存必须早于拦截器，否则验签时读不到 body。拦截器只挂在 {@code /api/v1/**}：
 * Actuator 与 SpringDoc 不属于契约的鉴权面，挂上会让探针不可用、文档打不开。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Configuration
public class OpenApiWebConfig implements WebMvcConfigurer {

    private static final int CACHED_BODY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final SignatureAuthInterceptor signatureAuthInterceptor;

    /**
     * @param signatureAuthInterceptor 验签与限流拦截器
     */
    public OpenApiWebConfig(SignatureAuthInterceptor signatureAuthInterceptor) {
        this.signatureAuthInterceptor = signatureAuthInterceptor;
    }

    /**
     * 注册链路 ID 过滤器：最先执行，保证所有响应（含 401/429）都带 {@code X-Trace-Id}。
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 注册请求体缓存过滤器：晚于链路 ID、早于 MVC 拦截器。
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<CachedBodyRequestFilter> cachedBodyRequestFilter() {
        FilterRegistrationBean<CachedBodyRequestFilter> registration =
                new FilterRegistrationBean<>(new CachedBodyRequestFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(CACHED_BODY_FILTER_ORDER);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(signatureAuthInterceptor).addPathPatterns("/api/v1/**");
    }
}
