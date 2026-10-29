package com.example.device.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Kafka 消息信封（{@code api-contract#x-kafka-message-contracts} 的 envelope-schema）。
 * <p>
 * {@code msgType} 保留为<b>原始字符串</b>而不是枚举：未知 {@code msg_type} 要在校验阶段判为
 * "计数 + WARN + ack"（R11），若在解析期就转枚举会把这条分支和"解析失败落死信"（R12）混成一件事。
 * {@code rawText} 是原始字节解码后的文本，死信表要原样保存、不做任何修复。
 * </p>
 *
 * @param source   来源系统编码
 * @param msgId    消息 ID，同一 source 内唯一
 * @param msgType  消息类型原始值
 * @param sn       设备序列号，同时是分区键
 * @param data     业务字段，key 为列名
 * @param eventTime 业务事件时间原文；本期契约缺该字段，不参与乱序判定
 * @param rawText  原始消息体文本
 * @author houzhangbo
 * @since 2026-10-29
 */
public record MessageEnvelope(
        String source,
        String msgId,
        String msgType,
        String sn,
        Map<String, JsonNode> data,
        String eventTime,
        String rawText) {
}
