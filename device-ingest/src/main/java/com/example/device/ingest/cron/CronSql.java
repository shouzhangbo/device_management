package com.example.device.ingest.cron;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 分区名与分区边界的<b>唯一</b>生成器（R14：动态 SQL 标识符只能来自代码常量或本类的受控生成）。
 * <p>
 * 任务不接受任何外部入参：分区名由 {@link YearMonth} 推出，边界由月份推出，
 * 拼进 SQL 之前还会再过一遍正则，避免"某天有人给任务加了个参数"直接把注入面打开。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class CronSql {

    /** 兜底分区名，永远存在且必须为空；禁止删除。 */
    public static final String PMAX = "pmax";

    private static final Pattern PARTITION_NAME = Pattern.compile("^p\\d{6}$");

    private static final Pattern BOUNDARY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final DateTimeFormatter MONTH_TAG = DateTimeFormatter.ofPattern("yyyyMM");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private CronSql() {
    }

    /**
     * 某个月对应的分区名。
     *
     * @param month 月份
     * @return 形如 {@code p202611}
     */
    public static String nextPartitionName(YearMonth month) {
        return "p" + month.format(MONTH_TAG);
    }

    /**
     * 某个月的分区上界（不含）：下个月 1 日。
     *
     * @param month 月份
     * @return 形如 {@code 2026-12-01}
     */
    public static String valuesLessThan(YearMonth month) {
        return month.plusMonths(1).atDay(1).format(DATE);
    }

    /**
     * 校验分区名，合法则原样返回。
     *
     * @param partition 分区名
     * @return 入参
     * @throws IllegalArgumentException 不是 {@code pYYYYMM} 形态
     */
    public static String requirePartition(String partition) {
        if (partition == null || !PARTITION_NAME.matcher(partition).matches()) {
            throw new IllegalArgumentException("illegal partition name: " + partition);
        }
        return partition;
    }

    /**
     * 校验分区边界日期，合法则原样返回。
     *
     * @param boundary 形如 {@code 2026-12-01}
     * @return 入参
     * @throws IllegalArgumentException 不是 {@code yyyy-MM-dd} 形态
     */
    public static String requireBoundary(String boundary) {
        if (boundary == null || !BOUNDARY.matcher(boundary).matches()) {
            throw new IllegalArgumentException("illegal partition boundary: " + boundary);
        }
        return boundary;
    }

    /**
     * 把分区名解析回月份，非 {@code pYYYYMM} 形态（含 {@code pmax}）返回 {@code null}。
     *
     * @param partition 分区名
     * @return 月份；无法解析时为 {@code null}
     */
    public static YearMonth monthOf(String partition) {
        if (partition == null || !PARTITION_NAME.matcher(partition).matches()) {
            return null;
        }
        return YearMonth.parse(partition.substring(1), MONTH_TAG);
    }
}
