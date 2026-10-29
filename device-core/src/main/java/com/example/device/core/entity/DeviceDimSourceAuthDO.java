package com.example.device.core.entity;

import java.time.LocalDateTime;

/**
 * 维度权威源授权配置行（{@code device_dim_source_auth}）。
 * <p>
 * 由 EP-012 在启动时全量加载进内存 Map、定时刷新，<b>不在消息热路径上查库</b>（{@code data-model} §7.5）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class DeviceDimSourceAuthDO {

    /** 主键。 */
    private Long id;

    /** 维度：FACTORY/ONLINE/BIND/RECOVER/PROFILE/MAINTAIN。 */
    private String dimType;

    /** 被授权来源：外部 source 编码或 {@code ADMIN}。 */
    private String source;

    /** 是否启用：0-关闭（视为未授权）1-启用。 */
    private Integer enabled;

    /** 授权说明，必须写清"为什么授权"。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
