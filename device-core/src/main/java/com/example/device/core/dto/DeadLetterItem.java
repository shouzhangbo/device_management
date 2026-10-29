package com.example.device.core.dto;

import java.time.LocalDateTime;

/**
 * 死信列表项（契约 {@code DeadLetterItem}，14 个字段）。
 * <p>
 * 不含 {@code content}（可达数百 KB，只在详情返回），也<b>不返回</b> DDL 的 {@code update_time}：
 * 那只是行级技术更新时间，暴露出去会被误读成"消息状态变更时间"。
 * </p>
 *
 * @param id            主键
 * @param msgId         消息 ID；信封不可解析时为 {@code UNKNOWN-<partition>-<offset>}
 * @param source        来源编码；信封损坏时为空串
 * @param topic         原始 Topic
 * @param consumerGroup 消费组
 * @param partitionNo   原始分区号
 * @param offsetNo      原始 Offset（重放定位依据）
 * @param msgKey        原始消息 key，即 sn
 * @param failReason    最后一次失败的异常摘要
 * @param retryCount    重试次数
 * @param status        0-待处理 1-已重放 2-已丢弃
 * @param handleUser    处理人拼音账号
 * @param handleTime    处理时间；未处理为 {@code null}
 * @param createTime    进入死信时间（告警与 SLA 计时起点）
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeadLetterItem(
        Long id,
        String msgId,
        String source,
        String topic,
        String consumerGroup,
        Integer partitionNo,
        Long offsetNo,
        String msgKey,
        String failReason,
        Integer retryCount,
        Integer status,
        String handleUser,
        LocalDateTime handleTime,
        LocalDateTime createTime) {

    /** 契约 {@code DeadLetterItem} 的字段数，CI 断言用。 */
    public static final int FIELD_COUNT = 14;
}
