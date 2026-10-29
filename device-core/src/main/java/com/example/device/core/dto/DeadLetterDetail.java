package com.example.device.core.dto;

import java.time.LocalDateTime;

/**
 * 死信详情：列表 14 字段 + {@code content}，与契约 {@code DeadLetterDetail} 的 {@code allOf} 拍平形状一致。
 *
 * @param id            主键
 * @param msgId         消息 ID
 * @param source        来源编码
 * @param topic         原始 Topic
 * @param consumerGroup 消费组
 * @param partitionNo   原始分区号
 * @param offsetNo      原始 Offset
 * @param msgKey        原始消息 key
 * @param failReason    最后一次失败的异常摘要
 * @param retryCount    重试次数
 * @param status        0-待处理 1-已重放 2-已丢弃
 * @param handleUser    处理人
 * @param handleTime    处理时间，未处理为 {@code null}
 * @param createTime    进入死信时间
 * @param content       原始消息体文本，<b>不做任何修复</b>
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeadLetterDetail(
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
        LocalDateTime createTime,
        String content) {
}
