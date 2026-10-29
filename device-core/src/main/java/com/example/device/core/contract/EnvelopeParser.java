package com.example.device.core.contract;

import com.example.device.core.config.DeviceIngestProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 信封解析器：字节 → {@link MessageEnvelope}。
 * <p>
 * 入参是<b>原始字节</b>而非已解析对象：{@code device-ingest} 用 {@code ByteArrayDeserializer}，
 * 死信表要保存原始内容（R12）。用 {@code JsonDeserializer<Dto>} 在框架层解析会丢失原文，本设计明确禁止。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class EnvelopeParser {

    /** 契约 {@code additionalProperties: false}：允许的顶层 key。 */
    private static final Set<String> ALLOWED_KEYS = Set.of("source", "msg_id", "msg_type", "sn", "data", "event_time");

    private final ObjectMapper objectMapper;
    private final int maxPayloadBytes;

    /**
     * 注入用于解析的 Jackson 映射器与消息体上限。
     *
     * @param objectMapper Jackson 映射器
     * @param properties   {@code device.ingest.max-payload-bytes} 的来源
     */
    public EnvelopeParser(ObjectMapper objectMapper, DeviceIngestProperties properties) {
        this.objectMapper = objectMapper;
        this.maxPayloadBytes = properties.getMaxPayloadBytes();
    }

    /**
     * 解析原始字节。
     *
     * @param payload Kafka 消息体，允许为 {@code null}
     * @return 解析结果；失败时调用方必须落死信而不是重试
     */
    public ParseResult parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ParseResult.fail(ParseResult.Status.EMPTY_PAYLOAD, "empty record value");
        }
        if (payload.length > maxPayloadBytes) {
            return ParseResult.fail(ParseResult.Status.OVERSIZE,
                    "payload bytes=" + payload.length + " limit=" + maxPayloadBytes);
        }
        String rawText = new String(payload, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return ParseResult.fail(ParseResult.Status.NOT_JSON, e.getOriginalMessage());
        } catch (java.io.IOException e) {
            return ParseResult.fail(ParseResult.Status.NOT_JSON, String.valueOf(e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            return ParseResult.fail(ParseResult.Status.NOT_OBJECT, "root json node is " + (root == null ? "null" : root.getNodeType()));
        }
        Iterator<String> keys = root.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!ALLOWED_KEYS.contains(key)) {
                return ParseResult.fail(ParseResult.Status.MALFORMED, "unexpected envelope key: " + key);
            }
        }
        String source = text(root, "source");
        String msgId = text(root, "msg_id");
        String msgType = text(root, "msg_type");
        String sn = text(root, "sn");
        JsonNode data = root.get("data");
        if (source == null || msgId == null || msgType == null || sn == null) {
            return ParseResult.fail(ParseResult.Status.MALFORMED, "required key missing among source/msg_id/msg_type/sn");
        }
        if (data == null || !data.isObject()) {
            return ParseResult.fail(ParseResult.Status.MALFORMED, "data must be a json object");
        }
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        data.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
        JsonNode eventTime = root.get("event_time");
        return ParseResult.ok(new MessageEnvelope(
                source, msgId, msgType, sn, fields,
                eventTime == null || eventTime.isNull() ? null : eventTime.asText(),
                rawText));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }
}
