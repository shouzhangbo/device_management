package com.example.device.core.applier;

import java.util.regex.Pattern;

/**
 * 一条待写入的列值绑定。
 * <p>
 * {@code column} 会以 {@code ${c.column}} 拼进 SQL 标识符位置（标识符无法用 {@code #{}} 绑定），
 * 因此构造时强制校验标识符形状；值侧一律用 {@code #{}}。这是 {@code tech-design} R14 的落地点。
 * </p>
 *
 * @param column 列名，只能来自 {@code DimSpec} 常量
 * @param value  绑定值，类型为 String / Integer / LocalDateTime
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ColumnBinding(String column, Object value) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    /**
     * 构造绑定并校验标识符形状。
     *
     * @param column 列名
     * @param value  值
     */
    public ColumnBinding {
        if (column == null || !IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("illegal SQL identifier: " + column);
        }
    }

    /**
     * MyBatis 属性访问入口：OGNL 在 XML 里以 {@code ${c.column}} / {@code #{c.value}} 取值，
     * 依赖的是 {@code getXxx()} 约定而不是 record 访问器，因此必须显式提供，不能假定框架版本支持。
     *
     * @return 列名
     */
    public String getColumn() {
        return column;
    }

    /**
     * MyBatis 值绑定入口。
     *
     * @return 列值
     */
    public Object getValue() {
        return value;
    }
}
