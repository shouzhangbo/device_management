package com.example.device.openapi.web;

import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 读路径的依赖失败翻译：回源超时或连不上数据库 → {@code 503/5030}。
 * <p>
 * Redis 故障在 {@code DeviceInfoCache} 内部降级为"直接查库"，不会走到这里；只有<b>连库也失败</b>时
 * 才会命中本类，这与 {@code tech-design} §9.6 的"Redis 与 DB 同时失败 → fail-fast 503/5030"一致。
 * 其它数据访问异常（SQL 语法错、约束冲突）不属于"依赖不可用"，仍由兜底返回 {@code 500/5000}，
 * 否则会把代码缺陷伪装成外部依赖故障。
 * </p>
 * <p>
 * 优先级必须高于 core 的 {@code GlobalExceptionHandler}：后者对 {@code Exception} 有兜底分支，
 * 不让本类先匹配就会一律变成 500。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DependencyFailureAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyFailureAdvice.class);

    /**
     * @param e 依赖失败异常
     * @return 503 + {@code 5030}
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<ApiEnvelope<Void>> onUnavailable(RuntimeException e) {
        LOG.error("read path dependency unavailable, fail fast with 503/5030", e);
        return ResponseEntity.status(ErrorCode.DEPENDENCY_UNAVAILABLE.status())
                .body(ApiEnvelope.error(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
