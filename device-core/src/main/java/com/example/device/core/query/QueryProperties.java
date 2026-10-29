package com.example.device.core.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device.query.*} 的类型安全绑定。
 * <p>
 * 本域只登记了这一个键（{@code tech-design} §11.1），不自造其余参数。
 * </p>
 *
 * @param batchMax EP-002 单次请求的 SN 数量上限
 * @author houzhangbo
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "device.query")
public record QueryProperties(Integer batchMax) {

    /** 设计文档建议值 100（待需求方确认），由 MGET 往返次数与响应包体积共同约束。 */
    public static final int DEFAULT_BATCH_MAX = 100;

    /**
     * 生效的批量上限。
     *
     * @return 正整数上限，非正或缺键时回落建议值
     */
    public int batchMaxOrDefault() {
        return batchMax == null || batchMax <= 0 ? DEFAULT_BATCH_MAX : batchMax;
    }
}
