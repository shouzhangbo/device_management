package com.example.device.core.contract;

import com.example.device.core.applier.ColumnBinding;
import com.example.device.core.applier.DimColumns;
import com.example.device.core.dim.DimSpec;
import com.example.device.core.dim.DimType;
import com.example.device.core.dim.MsgType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 信封语义校验器：{@link MessageEnvelope} → {@link ValidatedCommand}。
 * <p>
 * 三类拒绝的<b>处置不同</b>，因此必须在这里分清（{@code tech-design} R11 / R12）：
 * <ul>
 *   <li>{@code MALFORMED} / {@code OVERSIZE}——信封不可解析，落死信；</li>
 *   <li>{@code INVALID_PAYLOAD}——白名单越界或值非法，事务外计数 + WARN + ack；</li>
 *   <li>{@code UNKNOWN_MSG_TYPE}——未知类型，同上，<b>不落死信</b>（脏消息卡分区会让整个 Topic 积压）。</li>
 * </ul>
 * 本类<b>不抛异常</b>：任何一条消息都不应因为语义非法而中断分区消费。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class EnvelopeValidator {

    /** {@code data} 里携带外部透传信息的 key（{@code data-model} §7.1 {@code ext_json} 行）。 */
    public static final String EXT_KEY = "_ext";

    /**
     * 校验信封。
     *
     * @param envelope 解析成功的信封
     * @return 校验结果；{@link ValidateStatus#OK} 时携带 {@link ValidatedCommand}
     */
    public ValidateResult validate(MessageEnvelope envelope) {
        if (envelope == null) {
            return ValidateResult.invalid("envelope is null");
        }
        MsgType msgType = find(envelope.msgType());
        if (msgType == null) {
            return ValidateResult.unknownType(envelope.msgType());
        }
        String identityError = checkIdentity(envelope);
        if (identityError != null) {
            return ValidateResult.invalid(identityError);
        }
        DimType dim = DimSpec.dimOf(msgType);
        Map<String, JsonNode> data = new LinkedHashMap<>(envelope.data());
        try {
            String extJson = extractExt(data);
            if (data.isEmpty()) {
                return ValidateResult.invalid("data has no column of dim " + dim);
            }
            List<ColumnBinding> columns = DimColumns.fromJson(dim, data);
            return ValidateResult.ok(new ValidatedCommand(
                    envelope.sn(), envelope.source(), envelope.msgId(), msgType, dim, columns, extJson));
        } catch (IllegalArgumentException e) {
            return ValidateResult.invalid(e.getMessage());
        }
    }

    /**
     * 取 {@code _ext} 的原始 JSON 文本并从 {@code data} 中移除。
     * <p>
     * 移除是必须的：留在 map 里会被白名单判成越界列。本系统不解析其内部结构，
     * 也不允许它的 key 变成列名（{@code tech-design} R14）。
     * </p>
     *
     * @param data {@code data} 的可变副本
     * @return JSON 文本；缺省或 JSON {@code null} 时返回 {@code null}，表示本次不改 {@code ext_json} 列
     * @throws IllegalArgumentException {@code _ext} 不是对象或数组
     */
    private static String extractExt(Map<String, JsonNode> data) {
        JsonNode ext = data.remove(EXT_KEY);
        if (ext == null || ext.isNull()) {
            return null;
        }
        if (!ext.isObject() && !ext.isArray()) {
            throw new IllegalArgumentException(EXT_KEY + " must be a json object or array");
        }
        return ext.toString();
    }

    private static String checkIdentity(MessageEnvelope env) {
        if (env.sn().length() > PayloadLimits.MAX_SN_LENGTH
                || !env.sn().matches(PayloadLimits.SN_PATTERN)) {
            return "sn violates contract length/pattern";
        }
        if (env.source().length() > PayloadLimits.MAX_ID_LENGTH) {
            return "source exceeds length " + PayloadLimits.MAX_ID_LENGTH;
        }
        if (env.msgId().length() > PayloadLimits.MAX_ID_LENGTH) {
            return "msg_id exceeds length " + PayloadLimits.MAX_ID_LENGTH;
        }
        return null;
    }

    private static MsgType find(String raw) {
        for (MsgType t : MsgType.values()) {
            if (t.name().equals(raw)) {
                return t;
            }
        }
        return null;
    }
}
