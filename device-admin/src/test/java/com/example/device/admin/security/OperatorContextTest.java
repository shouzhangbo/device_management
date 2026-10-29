package com.example.device.admin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 操作人只从 JWT {@code sub} 取值：无认证、非 JWT 认证、空 {@code sub} 一律 401/4011。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class OperatorContextTest {

    private final OperatorContext operatorContext = new OperatorContext();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtSubjectIsTheOperator() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("zhangsan")));

        assertEquals("zhangsan", operatorContext.current());
    }

    @Test
    void noAuthenticationIsUnauthorized() {
        BizException thrown = assertThrows(BizException.class, operatorContext::current);

        assertEquals(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, thrown.errorCode());
    }

    @Test
    void nonJwtAuthenticationIsUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("zhangsan", "n/a"));

        BizException thrown = assertThrows(BizException.class, operatorContext::current);

        assertEquals(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, thrown.errorCode());
    }

    @Test
    void blankSubjectIsUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt(" ")));

        BizException thrown = assertThrows(BizException.class, operatorContext::current);

        assertEquals(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, thrown.errorCode());
        assertTrue(thrown.getMessage().contains("not authenticated"));
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject(subject)
                .claim("iss", "issuer-x")
                .build();
    }
}
