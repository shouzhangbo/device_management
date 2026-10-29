package com.example.device.core.web;

import org.slf4j.MDC;

/**
 * {@code trace_id} 的唯一读写入口。
 * <p>
 * 规则来自 {@code tech-design} §9.6：入站带 {@code X-Trace-Id} 则沿用，否则服务端生成；
 * 响应头与响应体的值恒等；同时进 MDC，使日志行能把一次请求串起来（日志规范【强制】）。
 * 业务代码一律用 {@link #current()} 取值，禁止把 traceId 当方法参数手工传递。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class TraceContext {

    /** MDC 键名，与结构化日志字段、响应体 {@code trace_id} 同串。 */
    public static final String MDC_KEY = "trace_id";

    /** 请求/响应头名。 */
    public static final String HEADER = "X-Trace-Id";

    /** 沿用外部 traceId 时的长度上限：防止超长头撑爆日志与响应。 */
    public static final int MAX_LENGTH = 64;

    private TraceContext() {
    }

    /**
     * 当前请求的链路 ID。
     *
     * @return MDC 中的值；非请求线程（定时任务、消费线程未注入时）返回空串而不是 {@code null}
     */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "" : value;
    }

    /** 生成链路 ID 时使用的随机源：必须是加密安全随机，否则同毫秒并发请求可能撞出同一个 ID。 */
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * 生成一个新链路 ID：32 位大写十六进制，与契约示例同为不透明短串。
     *
     * @return 新生成的 traceId
     */
    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 清洗外部传入的 traceId：只允许字母数字与 {@code -_}，超长截断；不合格则视为未提供。
     * <p>
     * 直接沿用调用方任意字符串会把换行等控制字符带进日志行（日志注入），并使响应头非法。
     * </p>
     *
     * @param raw 请求头原值，允许为 {@code null}
     * @return 可用值；不合格时返回 {@code null} 表示需要服务端生成
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return trimmed;
    }
}
