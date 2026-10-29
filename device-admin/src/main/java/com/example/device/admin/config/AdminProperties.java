package com.example.device.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device.admin.*} 的类型安全绑定（{@code tech-design} §11.1 已登记这四个键）。
 * <p>
 * {@code jwt} 三键没有默认值：缺任何一个都让进程拒绝启动（{@code SecurityConfig} 断言），
 * 不允许用"默认 issuer"或内置公钥兜底。
 * </p>
 *
 * @param maxOffset 深翻页上限（EP-003 / EP-010 / EP-011）
 * @param jwt       JWT 校验参数
 * @author houzhangbo
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "device.admin")
public record AdminProperties(Integer maxOffset, Jwt jwt) {

    /** 设计文档建议的深翻页上限。 */
    public static final int DEFAULT_MAX_OFFSET = 10_000;

    /**
     * 生效的深翻页上限。
     *
     * @return 非负上限，缺键或负数时回落建议值
     */
    public int maxOffsetOrDefault() {
        return maxOffset == null || maxOffset < 0 ? DEFAULT_MAX_OFFSET : maxOffset;
    }

    /**
     * JWT 资源服务器参数。
     *
     * @param issuer      期望的 {@code iss}
     * @param audience    期望的 {@code aud}
     * @param publicKeyPath 验签公钥挂载文件（PEM，X.509 SubjectPublicKeyInfo）
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record Jwt(String issuer, String audience, String publicKeyPath) {
    }
}
