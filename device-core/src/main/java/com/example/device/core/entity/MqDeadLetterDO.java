package com.example.device.core.entity;

import java.time.LocalDateTime;

/**
 * 死信归档行（{@code mq_dead_letter}）。
 * <p>
 * 存<b>原始字节解码后的消息文本</b>，不做任何修复；{@code msg_id} 无唯一约束，同一条消息可能反复进死信。
 * 响应面只暴露业务时间（{@code create_time} 进死信、{@code handle_time} 人工处理），不暴露 {@code updateTime}
 * （{@code api-contract} 的 {@code DeadLetterItem} 描述）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class MqDeadLetterDO {

    /** 主键。 */
    private Long id;

    /** 消息 ID；信封不可解析时填 {@code UNKNOWN-<partition>-<offset>}。 */
    private String msgId;

    /** 来源；信封损坏时空串。 */
    private String source;

    /** 原始 Topic。 */
    private String topic;

    /** 消费组，多消费组订阅时区分死信归属。 */
    private String consumerGroup;

    /** 原始分区号。 */
    private Integer partitionNo;

    /** 原始 Offset，重放定位依据。 */
    private Long offsetNo;

    /** 原始消息 key，即 SN。 */
    private String msgKey;

    /** 原始消息体文本。 */
    private String content;

    /** 最后一次失败的异常摘要。 */
    private String failReason;

    /** 已重试次数，恒等于配置的最大重试次数。 */
    private Integer retryCount;

    /** 处理状态：0-待处理 1-已重放 2-已丢弃。 */
    private Integer status;

    /** 处理人（拼音账号），死信操作等同一次后台编辑，必须留痕。 */
    private String handleUser;

    /** 处理时间，未处理时为哨兵值，出口转 {@code null}。 */
    private LocalDateTime handleTime;

    /** 进入死信时间，告警与 SLA 计时起点。 */
    private LocalDateTime createTime;

    /** 更新时间，行级技术字段。 */
    private LocalDateTime updateTime;
}
