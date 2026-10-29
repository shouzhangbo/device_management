package com.example.device.core.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 时间值的集中转换点。
 * <p>
 * 三条口径在这里一次定死，别处不再各写一份：
 * ① 库里存<b>本地</b> {@code DATETIME}，因此落库用 {@link LocalDateTime}，时区只在序列化层补（见 {@code JacksonConfig}）；
 * ② "未同步"用哨兵 {@code 1970-01-01 00:00:00} 而不是 {@code NULL}（列 {@code NOT NULL}），出口再转 {@code null}；
 * ③ 外部消息可能给 ISO 8601 字符串，也可能给 epoch 毫秒数，两种都要收，转换失败即视为非法载荷。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class TimeValues {

    /** 哨兵时间：外部尚未同步该列。DB 侧不允许 {@code NULL}，出口必须转 {@code null}。 */
    public static final LocalDateTime SENTINEL = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private TimeValues() {
    }

    /**
     * 宽松解析外部时间值。
     *
     * @param raw 输入：ISO 8601（带或不带偏移）或 epoch 毫秒数字
     * @return 解析后的本地时间
     * @throws IllegalArgumentException 无法解析时抛出，由校验层转成载荷非法
     */
    public static LocalDateTime parse(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("time value is null");
        }
        if (raw instanceof LocalDateTime dt) {
            return dt;
        }
        if (raw instanceof Number n) {
            return ofEpochMilli(n.longValue());
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("time value is blank");
        }
        if (text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return ofEpochMilli(Long.parseLong(text));
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地格式：外部部分系统不带偏移量。
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unparsable time value: " + text, e);
        }
    }

    /**
     * epoch 毫秒 → 本地时间，按 JVM 默认时区解释（与 DDL 的 {@code DATETIME} 存储口径一致）。
     *
     * @param epochMilli 毫秒时间戳
     * @return 本地时间
     */
    public static LocalDateTime ofEpochMilli(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    /**
     * 哨兵值转 {@code null}，只在装配层调用（Mapper 层转换会让流水 {@code before_json} 与库值不一致）。
     *
     * @param value 库值
     * @return 哨兵时返回 {@code null}，否则原值
     */
    public static LocalDateTime nullIfSentinel(LocalDateTime value) {
        return SENTINEL.equals(value) ? null : value;
    }

    /**
     * {@code null} 回落哨兵值：写库路径不允许 {@code NULL}。
     *
     * @param value 输入值
     * @return 非空值
     */
    public static LocalDateTime sentinelIfNull(LocalDateTime value) {
        return value == null ? SENTINEL : value;
    }

    /**
     * 是否为哨兵值。
     *
     * @param value 库值
     * @return 是否表示"未同步"
     */
    public static boolean isSentinel(LocalDateTime value) {
        return SENTINEL.equals(value);
    }
}
