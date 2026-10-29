package com.example.device.core.entity;

import java.time.LocalDateTime;

/**
 * 消费幂等记录（{@code mq_consume_record}）。
 * <p>
 * 本表在事务内<b>最先</b>写入，{@code uk_source_msg} 的唯一索引冲突是幂等判定的唯一依据（{@code tech-design} R2）。
 * 该表<b>不分区</b>：分区会迫使唯一键加入分区键，从而让跨月重投的重复消息被静默放过（{@code data-model} §8.3）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class MqConsumeRecordDO {

    /** 主键。 */
    private Long id;

    /** 信封消息 ID，幂等键的一半。 */
    private String msgId;

    /** 消息来源系统编码，幂等键的另一半（跨源 ID 碰撞是常态）。 */
    private String source;

    /** 业务类型，本域固定 {@code device}。 */
    private String bizType;

    /** 业务 ID，即设备 SN。 */
    private String bizId;

    /** 消息类型。 */
    private String msgType;

    /** 来源 Topic。 */
    private String topic;

    /** 消费结果：1-成功（终态）2-失败待重试 3-已进死信。 */
    private Integer status;

    /** 失败原因摘要，截断到 1000 字符；异常栈只进日志。 */
    private String failReason;

    /** 消费时间，清理与保留期依据。 */
    private LocalDateTime consumeTime;

    /** 更新时间，状态回写时由数据库自动维护。 */
    private LocalDateTime updateTime;
}
