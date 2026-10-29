package com.example.device.core.entity;

import java.time.LocalDateTime;

/**
 * 设备维度变更流水行（{@code device_info_change_record}，只增不改、按月分区）。
 * <p>
 * 主键为 {@code (id, create_time)}：MySQL 分区表要求分区键出现在每个唯一键中（{@code data-model} §6.2）。
 * 本表记录"每次处理尝试"，因此 {@code (source, msg_id)} 不建唯一索引（§8.2 裁决 4）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class DeviceInfoChangeRecordDO {

    /** 主键（分区表复合主键的一部分）。 */
    private Long id;

    /** 设备序列号，与主档无外键约束：设备不存在也要留痕。 */
    private String sn;

    /** 变更来源：外部 source 编码或 {@code ADMIN}。 */
    private String source;

    /** 消息 ID；后台操作填本地生成的 {@code ADM-<uuid>}。 */
    private String msgId;

    /** 消息类型，保留原始值便于外部按类型对账。 */
    private String msgType;

    /** 本次变更影响的维度；逻辑删除填空串。 */
    private String dimType;

    /** 变更前该维度列值快照的 JSON 文本。 */
    private String beforeJson;

    /** 变更后该维度列值快照的 JSON 文本。 */
    private String afterJson;

    /** 处理结果：1-已更新 3-设备不存在 4-未授权来源。 */
    private Integer result;

    /** 操作人：后台填拼音账号，消息来源为空串。 */
    private String opUser;

    /** 落库时间，同时是分区键。 */
    private LocalDateTime createTime;

    /** 规范必备字段；流水只增不改，无更新路径。 */
    private LocalDateTime updateTime;
}
