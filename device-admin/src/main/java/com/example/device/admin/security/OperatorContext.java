package com.example.device.admin.security;

import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 当前操作人（JWT {@code sub}，即拼音账号）。
 * <p>
 * 写库时的 {@code maintain_user} / {@code op_user} / {@code handle_user} <b>只</b>从这里取值：
 * 不允许从请求体接收"操作人"，否则调用方能伪造审计痕迹（{@code data-model} §7.1）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class OperatorContext {

    /**
     * 当前已认证的操作人。
     *
     * @return JWT {@code sub}
     * @throws BizException {@code 401/4011} 无认证信息或 {@code sub} 为空
     */
    public String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        throw new BizException(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, "not authenticated");
    }
}
