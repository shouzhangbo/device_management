package com.example.device.core.auth;

import com.example.device.core.dim.DimType;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 维度授权判定：纯内存查表，不写库、不产生副作用。
 * <p>
 * 这是"无业务事件时间"缺口下的核心防线（{@code tech-design} §6.3）：一条消息能不能覆盖某维度，
 * 只取决于该维度是否在配置表里显式授权了这个 source。
 * <b>查不到就当允许</b>是不允许的默认放行——写错维度比拒绝一条消息代价大得多。
 * </p>
 * <p>
 * {@code ADMIN} 走同一张表，代码里没有"后台永远允许"的特权分支。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DimAuthGuard {

    /** 判定结论。 */
    public enum Decision {
        /** 已授权，允许写该维度。 */
        ALLOW,
        /** 未授权。消费侧计数 + WARN + ack（不进事务、不写流水）；后台侧转 {@code 403/4031}。 */
        DENY
    }

    private final DimAuthCache authCache;

    /**
     * @param authCache 授权快照
     */
    public DimAuthGuard(DimAuthCache authCache) {
        this.authCache = authCache;
    }

    /**
     * 判定某来源是否被授权写某维度。
     *
     * @param dim    维度
     * @param source 来源编码
     * @return 判定结论
     */
    public Decision check(DimType dim, String source) {
        if (dim == null || source == null || source.isBlank()) {
            return Decision.DENY;
        }
        Set<String> authorized = authCache.snapshot().get(dim);
        return authorized != null && authorized.contains(source) ? Decision.ALLOW : Decision.DENY;
    }

    /**
     * 后台侧的判定：未授权直接抛业务异常，映射为 {@code 403/4031}。
     *
     * @param dim    维度
     * @param source 来源编码
     * @throws BizException 未授权
     */
    public void checkOrThrow(DimType dim, String source) {
        if (check(dim, source) == Decision.DENY) {
            throw new BizException(ErrorCode.DIM_NOT_AUTHORIZED,
                    "dimension " + dim + " is not authorized for source " + source);
        }
    }
}
