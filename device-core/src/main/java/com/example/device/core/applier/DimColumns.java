package com.example.device.core.applier;

import com.example.device.core.dim.DimColumn;
import com.example.device.core.dim.DimSpec;
import com.example.device.core.dim.DimType;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.support.TimeValues;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白名单到列绑定的唯一转换点：把外部 {@code data}（或后台命令）变成 {@link ColumnBinding} 列表。
 * <p>
 * 越界 key、类型不符、值域越界、超长字符串都在这一步抛
 * {@link IllegalArgumentException}，由校验层转成"载荷非法"。
 * <b>这是 {@code tech-design} R14 的第二道防线</b>：即使上游漏判，也拼不出越界列名，
 * 因为 {@link ColumnBinding} 的构造器还会再断言一次标识符形状。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class DimColumns {

    /** {@code register_status} 的列名常量：建档置位要用，但不能让调用方手拼字符串。 */
    public static final String REGISTER_STATUS = "register_status";

    /**
     * {@code ext_json} 的列名常量：它不属于任何维度，是白名单之外唯一允许写的列，
     * 且标识符同样来自代码常量（R14）。
     */
    public static final String EXT_JSON = "ext_json";

    private DimColumns() {
    }

    /**
     * 把列绑定写回 DO，供建档分支构造待插入行。
     * <p>
     * 列名到属性的映射必须由代码穷举：一旦白名单加了列而这里漏改，立刻抛
     * {@link IllegalStateException}，而不是让新列静默不生效。
     * </p>
     *
     * @param row     目标 DO
     * @param columns 列绑定
     */
    public static void populate(DeviceInfoDO row, List<ColumnBinding> columns) {
        for (ColumnBinding c : columns) {
            switch (c.column()) {
                case "factory_code" -> row.setFactoryCode((String) c.value());
                case "factory_name" -> row.setFactoryName((String) c.value());
                case "produce_time" -> row.setProduceTime((LocalDateTime) c.value());
                case REGISTER_STATUS -> row.setRegisterStatus((Integer) c.value());
                case "online_status" -> row.setOnlineStatus((Integer) c.value());
                case "bind_status" -> row.setBindStatus((Integer) c.value());
                case "bind_target" -> row.setBindTarget((String) c.value());
                case "bind_time" -> row.setBindTime((LocalDateTime) c.value());
                case "recover_status" -> row.setRecoverStatus((Integer) c.value());
                case "scrap_time" -> row.setScrapTime((LocalDateTime) c.value());
                case "model_code" -> row.setModelCode((String) c.value());
                case "model_name" -> row.setModelName((String) c.value());
                case "remark" -> row.setRemark((String) c.value());
                case "image_url" -> row.setImageUrl((String) c.value());
                case "maintain_user" -> row.setMaintainUser((String) c.value());
                case "maintain_time" -> row.setMaintainTime((LocalDateTime) c.value());
                case EXT_JSON -> row.setExtJson((String) c.value());
                default -> throw new IllegalStateException("no populate branch for column " + c.column());
            }
        }
    }

    /**
     * 按维度白名单把消息 {@code data} 转成列绑定。
     *
     * @param dim  维度
     * @param data 消息业务字段，key 必须全部命中该维度白名单
     * @return 列绑定列表，顺序与白名单一致（保证流水快照可复现）
     * @throws IllegalArgumentException 越界 key / 类型不符 / 值域或长度越界
     */
    public static List<ColumnBinding> fromJson(DimType dim, Map<String, JsonNode> data) {
        List<DimColumn> whitelist = DimSpec.columnsOf(dim);
        for (String key : data.keySet()) {
            if (whitelist.stream().noneMatch(c -> c.column().equals(key))) {
                throw new IllegalArgumentException(
                        "column not allowed for dim " + dim + ": " + key);
            }
        }
        return whitelist.stream()
                .filter(c -> data.containsKey(c.column()))
                .map(c -> new ColumnBinding(c.column(), coerce(c, data.get(c.column()))))
                .toList();
    }

    /**
     * 后台命令侧的转换：值已是 Java 类型，只做白名单与边界校验。
     *
     * @param dims   参与本次写入的维度（后台编辑同时涉及 PROFILE 与 MAINTAIN）
     * @param values {@code 列名 -> 值}，key 必须落在 {@code dims} 的白名单并集内
     * @return 列绑定列表
     * @throws IllegalArgumentException 越界 key 或值不合法
     */
    public static List<ColumnBinding> fromValues(List<DimType> dims, Map<String, Object> values) {
        for (String key : values.keySet()) {
            if (dims.stream().noneMatch(d -> DimSpec.allows(d, key))) {
                throw new IllegalArgumentException("column not allowed for dims " + dims + ": " + key);
            }
        }
        return dims.stream()
                .flatMap(d -> DimSpec.columnsOf(d).stream())
                .filter(c -> values.containsKey(c.column()))
                .map(c -> new ColumnBinding(c.column(), checkRange(c, values.get(c.column()))))
                .toList();
    }

    /**
     * 建档时的注册状态置位列。
     *
     * @return {@code register_status = 1} 的绑定
     */
    public static ColumnBinding registerStatusOn() {
        return new ColumnBinding(REGISTER_STATUS, 1);
    }

    /**
     * 取某维度在库中的当前列值，供流水 {@code before_json} 使用。
     * <p>
     * <b>只快照本维度的列</b>，不做整行快照（{@code data-model} §8.4：避免流水膨胀）。
     * </p>
     *
     * @param dims 需要快照的维度（后台编辑传两个）
     * @param row  当前行
     * @return 保持列顺序的快照
     */
    public static Map<String, Object> snapshot(List<DimType> dims, DeviceInfoDO row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (DimType dim : dims) {
            for (DimColumn c : DimSpec.columnsOf(dim)) {
                out.put(c.column(), columnValue(c.column(), row));
            }
        }
        return out;
    }

    /**
     * 由列绑定列表生成 {@code after_json} 快照。
     *
     * @param columns 列绑定
     * @return 快照
     */
    public static Map<String, Object> snapshotOfBindings(List<ColumnBinding> columns) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ColumnBinding c : columns) {
            out.put(c.column(), c.value());
        }
        return out;
    }

    private static Object coerce(DimColumn column, JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("value of " + column.column() + " is null");
        }
        return switch (column.kind()) {
            case STRING -> {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("column " + column.column() + " expects a string");
                }
                String text = node.textValue();
                if (text.length() > column.limit()) {
                    throw new IllegalArgumentException(
                            "column " + column.column() + " exceeds length " + column.limit());
                }
                yield text;
            }
            case INTEGER -> {
                if (!node.isIntegralNumber()) {
                    throw new IllegalArgumentException("column " + column.column() + " expects an integer");
                }
                int value = node.intValue();
                if (value < 0 || value > column.limit()) {
                    throw new IllegalArgumentException("column " + column.column()
                            + " out of range [0," + column.limit() + "]: " + value);
                }
                yield value;
            }
            case DATETIME -> {
                LocalDateTime parsed = node.isNumber()
                        ? TimeValues.ofEpochMilli(node.longValue())
                        : TimeValues.parse(node.isTextual() ? node.textValue() : node.asText());
                yield parsed;
            }
        };
    }

    private static Object checkRange(DimColumn column, Object raw) {
        return switch (column.kind()) {
            case STRING -> {
                if (!(raw instanceof String text)) {
                    throw new IllegalArgumentException("column " + column.column() + " expects a string");
                }
                if (text.length() > column.limit()) {
                    throw new IllegalArgumentException(
                            "column " + column.column() + " exceeds length " + column.limit());
                }
                yield text;
            }
            case INTEGER -> {
                if (!(raw instanceof Number n)) {
                    throw new IllegalArgumentException("column " + column.column() + " expects an integer");
                }
                int value = n.intValue();
                if (value < 0 || value > column.limit()) {
                    throw new IllegalArgumentException("column " + column.column()
                            + " out of range [0," + column.limit() + "]: " + value);
                }
                yield value;
            }
            case DATETIME -> {
                if (!(raw instanceof LocalDateTime)) {
                    throw new IllegalArgumentException("column " + column.column() + " expects a datetime");
                }
                yield raw;
            }
        };
    }

    private static Object columnValue(String column, DeviceInfoDO row) {
        return switch (column) {
            case "factory_code" -> row.getFactoryCode();
            case "factory_name" -> row.getFactoryName();
            case "produce_time" -> row.getProduceTime();
            case "register_status" -> row.getRegisterStatus();
            case "online_status" -> row.getOnlineStatus();
            case "bind_status" -> row.getBindStatus();
            case "bind_target" -> row.getBindTarget();
            case "bind_time" -> row.getBindTime();
            case "recover_status" -> row.getRecoverStatus();
            case "scrap_time" -> row.getScrapTime();
            case "model_code" -> row.getModelCode();
            case "model_name" -> row.getModelName();
            case "remark" -> row.getRemark();
            case "image_url" -> row.getImageUrl();
            case "maintain_user" -> row.getMaintainUser();
            case "maintain_time" -> row.getMaintainTime();
            default -> throw new IllegalStateException("no snapshot accessor for column " + column);
        };
    }
}
