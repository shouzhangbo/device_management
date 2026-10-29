package com.example.device.core.dim;

/**
 * 维度白名单里的一个可写列。
 * <p>
 * 构造即校验标识符形状（{@code tech-design} R14：动态 SQL 标识符只能来自代码常量）。
 * 列名最终会以 {@code ${c.column}} 拼进 SQL，因此<b>任何</b>来自消息或外部输入的都不得构造成本类型。
 * </p>
 * <p>
 * {@code limit} 把 {@code data-model} §6.1 的列宽与状态值域带进白名单本身，
 * 让"超长字符串""状态越界"在校验阶段就被拒（{@code INVALID_PAYLOAD}），
 * 而不是等 MySQL 抛 {@code Data too long}——后者会让事务反复回滚、重试到死信，把一个坏字段变成一条死信。
 * </p>
 *
 * @param column 数据库列名，只允许小写下划线标识符
 * @param kind   值类型，决定 JSON 到 JDBC 参数的转换方式
 * @param limit  {@link ValueKind#STRING} 时为最大字符数，{@link ValueKind#INTEGER} 时为允许的最大值，
 *               {@link ValueKind#DATETIME} 时忽略
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DimColumn(String column, ValueKind kind, int limit) {

    private static final String IDENTIFIER = "^[a-z][a-z0-9_]{1,63}$";

    public DimColumn {
        if (column == null || !column.matches(IDENTIFIER)) {
            throw new IllegalArgumentException("illegal column identifier: " + column);
        }
    }

    /**
     * 创建一个字符串列白名单项。
     *
     * @param column 列名
     * @param maxLen DDL 列宽
     * @return 白名单项
     */
    public static DimColumn string(String column, int maxLen) {
        return new DimColumn(column, ValueKind.STRING, maxLen);
    }

    /**
     * 创建一个状态位列白名单项。
     *
     * @param column 列名
     * @param max    DDL 注释给出的取值上界（下界恒为 0）
     * @return 白名单项
     */
    public static DimColumn integer(String column, int max) {
        return new DimColumn(column, ValueKind.INTEGER, max);
    }

    /**
     * 创建一个时间列白名单项（出口哨兵值转 {@code null}）。
     *
     * @param column 列名
     * @return 白名单项
     */
    public static DimColumn datetime(String column) {
        return new DimColumn(column, ValueKind.DATETIME, 0);
    }

    /** 值类型。 */
    public enum ValueKind {
        /** VARCHAR 列。 */
        STRING,
        /** TINYINT / INT 列。 */
        INTEGER,
        /** DATETIME 列。 */
        DATETIME
    }
}
