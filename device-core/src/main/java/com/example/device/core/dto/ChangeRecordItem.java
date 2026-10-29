package com.example.device.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/**
 * 设备变更流水项（契约 {@code ChangeRecordItem}，9 个字段）。
 * <p>
 * {@code result} 只有 1/3/4 三种：<b>重复消息与版本冲突不落流水</b>（整事务回滚 / 重试→死信），
 * 这两类事实分别由 {@code mq_consume_record} 与死信表承载。
 * </p>
 *
 * @param createTime  变更落库时间（同时是分区键）
 * @param source      来源编码或 {@code ADMIN}
 * @param msgId       消息 ID；后台操作填 {@code ADM-<uuid>}
 * @param msgType     消息类型
 * @param dimType     本次影响的维度；逻辑删除为空串
 * @param beforeJson  变更前该维度列值快照（人工纠正的依据）
 * @param afterJson   变更后该维度列值快照
 * @param result      1-已更新 3-设备不存在 4-未授权来源
 * @param opUser      操作人拼音账号；消息来源为空串
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ChangeRecordItem(
        LocalDateTime createTime,
        String source,
        String msgId,
        String msgType,
        String dimType,
        JsonNode beforeJson,
        JsonNode afterJson,
        Integer result,
        String opUser) {
}
