package com.example.device.core.entity;

import java.time.LocalDateTime;

/**
 * 设备主档行（{@code device_info}，外部系统的本地镜像）。
 * <p>
 * 列定义的唯一真源是 {@code data-model} §6.1：<b>23</b> 列。
 * 时间列的哨兵值 {@code 1970-01-01 00:00:00} 表示"外部未同步"，出口统一转 {@code null}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class DeviceInfoDO {

    /** 主键，不对外。 */
    private Long id;

    /** 设备序列号：业务唯一键、Kafka 分区键、缓存 Key 主体。 */
    private String sn;

    /** 型号编码（PROFILE）。 */
    private String modelCode;

    /** 型号名称（PROFILE，冗余存名）。 */
    private String modelName;

    /** 工厂编码（FACTORY）。 */
    private String factoryCode;

    /** 工厂名称（FACTORY）。 */
    private String factoryName;

    /** 生产时间（FACTORY），哨兵表示未同步。 */
    private LocalDateTime produceTime;

    /** 出厂注册状态：0-未注册 1-已注册。 */
    private Integer registerStatus;

    /** 在线状态：0-未知 1-在线 2-离线。 */
    private Integer onlineStatus;

    /** 绑定状态：0-未绑定 1-已绑定。 */
    private Integer bindStatus;

    /** 绑定目标（语义由外部定义），解绑时为空串。 */
    private String bindTarget;

    /** 绑定时间，哨兵表示未同步。 */
    private LocalDateTime bindTime;

    /** 回收报废状态：0-正常 1-已回收 2-已报废。 */
    private Integer recoverStatus;

    /** 报废时间，哨兵表示未同步。 */
    private LocalDateTime scrapTime;

    /** 备注（PROFILE）。 */
    private String remark;

    /** 设备图片 URL（PROFILE，公网可读）。 */
    private String imageUrl;

    /** 最近维护人（拼音账号）：仅内部，不进缓存、不对外。 */
    private String maintainUser;

    /** 最近维护时间：仅内部，不进缓存、不对外。 */
    private LocalDateTime maintainTime;

    /** 外部透传附加信息的原始 JSON 文本：仅内部，不进缓存、不对外。 */
    private String extJson;

    /** 乐观锁版本，不对外（对外暴露会诱导调用方做错误的并发假设）。 */
    private Integer version;

    /** 逻辑删除：0-正常 1-已删除。 */
    private Integer deleted;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间，同时是 EP-007 缓存对账水位。 */
    private LocalDateTime updateTime;
}
