package com.example.device.core.dim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code msg_type → dim_type → 可写列} 的唯一真源。
 * <p>
 * 本类同时是 MyBatis 动态 {@code <set>} 的列白名单与信封字段白名单校验的依据（{@code data-model} §8.4）。
 * 禁止在 SQL 或 Service 里散落 {@code if (msgType.equals(...))}。
 * 静态初始化时用 {@link #assertInvariants()} 断言白名单形状，任何越界列在类加载期即失败。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class DimSpec {

    /** 后台档案编辑落到流水的 {@code msg_type}。 */
    public static final String ADMIN_PROFILE_UPDATE = "ADMIN_PROFILE_UPDATE";

    /** 后台建档落到流水的 {@code msg_type}。 */
    public static final String ADMIN_CREATE = "ADMIN_CREATE";

    /** 后台逻辑删除落到流水的 {@code msg_type}（该动作不属于任何维度，{@code dim_type} 填空串）。 */
    public static final String ADMIN_DELETE = "ADMIN_DELETE";

    /** 逻辑删除时的 {@code dim_type} 取值：不属任何维度，用空串而不是新造枚举值。 */
    public static final String NO_DIM = "";

    private static final Map<DimType, List<DimColumn>> COLUMNS = buildColumns();

    private static final Map<MsgType, DimType> MSG_TO_DIM = Map.of(
            MsgType.FACTORY_REGISTER, DimType.FACTORY,
            MsgType.ONLINE_CHANGE, DimType.ONLINE,
            MsgType.BIND_CHANGE, DimType.BIND,
            MsgType.RECOVER_CHANGE, DimType.RECOVER,
            MsgType.PROFILE_CHANGE, DimType.PROFILE);

    /** 只有出厂注册在 SN 不存在时建档，其余维度落流水 {@code result=3}（{@code tech-design} R5）。 */
    private static final java.util.Set<MsgType> CREATE_IF_ABSENT = java.util.EnumSet.of(MsgType.FACTORY_REGISTER);

    static {
        assertInvariants();
    }

    private DimSpec() {
    }

    /**
     * 取某维度的可写列白名单。
     *
     * @param dim 维度
     * @return 不可变列清单
     */
    public static List<DimColumn> columnsOf(DimType dim) {
        return COLUMNS.get(dim);
    }

    /**
     * 取某维度的列名清单（用于错误消息与白名单求交集）。
     *
     * @param dim 维度
     * @return 列名
     */
    public static List<String> namesOf(DimType dim) {
        return columnsOf(dim).stream().map(DimColumn::column).toList();
    }

    /**
     * 判断列名是否属于该维度的白名单。
     *
     * @param dim    维度
     * @param column 列名
     * @return 是否允许写
     */
    public static boolean allows(DimType dim, String column) {
        return namesOf(dim).contains(column);
    }

    /**
     * 列的元数据查找，用于按类型转换 JSON 值。
     *
     * @param dim    维度
     * @param column 列名
     * @return 白名单项，不属于该维度时为 empty
     */
    public static Optional<DimColumn> find(DimType dim, String column) {
        return columnsOf(dim).stream().filter(c -> c.column().equals(column)).findFirst();
    }

    /**
     * {@code msg_type → dim_type} 映射。
     *
     * @param msgType 消息类型
     * @return 维度
     */
    public static DimType dimOf(MsgType msgType) {
        DimType dim = MSG_TO_DIM.get(msgType);
        if (dim == null) {
            throw new IllegalArgumentException("no dim mapping for " + msgType);
        }
        return dim;
    }

    /**
     * 该消息类型在 SN 不存在时是否建档。
     *
     * @param msgType 消息类型
     * @return 是否建档
     */
    public static boolean createsDevice(MsgType msgType) {
        return CREATE_IF_ABSENT.contains(msgType);
    }

    /**
     * 后台档案编辑使用的维度。
     *
     * @return PROFILE 维度
     */
    public static DimType adminProfileDim() {
        return DimType.PROFILE;
    }

    /**
     * 启动期不变式断言：白名单必须非空、列名不得跨维度重复、维度集合必须与枚举同规模。
     * <p>
     * 任何一条不成立都意味着 {@code <set>} 可能拼出越界列，属结构性缺陷，故直接让类加载失败而不是等运行时。
     * </p>
     */
    static void assertInvariants() {
        if (COLUMNS.size() != DimType.values().length) {
            throw new IllegalStateException("DimSpec columns missing a dim type");
        }
        Map<String, DimType> seen = new LinkedHashMap<>();
        for (Map.Entry<DimType, List<DimColumn>> e : COLUMNS.entrySet()) {
            if (e.getValue().isEmpty()) {
                throw new IllegalStateException("empty column whitelist for " + e.getKey());
            }
            for (DimColumn c : e.getValue()) {
                DimType previous = seen.putIfAbsent(c.column(), e.getKey());
                if (previous != null) {
                    throw new IllegalStateException("column " + c.column() + " owned by both " + previous + " and " + e.getKey());
                }
            }
        }
        if (MSG_TO_DIM.size() != MsgType.values().length) {
            throw new IllegalStateException("DimSpec msg-type mapping missing a msg type");
        }
    }

    private static Map<DimType, List<DimColumn>> buildColumns() {
        Map<DimType, List<DimColumn>> m = new LinkedHashMap<>();
        m.put(DimType.FACTORY, List.of(
                DimColumn.string("factory_code", 64),
                DimColumn.string("factory_name", 128),
                DimColumn.datetime("produce_time"),
                DimColumn.integer("register_status", 1)));
        m.put(DimType.ONLINE, List.of(DimColumn.integer("online_status", 2)));
        m.put(DimType.BIND, List.of(
                DimColumn.integer("bind_status", 1),
                DimColumn.string("bind_target", 128),
                DimColumn.datetime("bind_time")));
        m.put(DimType.RECOVER, List.of(
                DimColumn.integer("recover_status", 2),
                DimColumn.datetime("scrap_time")));
        m.put(DimType.PROFILE, List.of(
                DimColumn.string("model_code", 64),
                DimColumn.string("model_name", 128),
                DimColumn.string("remark", 512),
                DimColumn.string("image_url", 512)));
        m.put(DimType.MAINTAIN, List.of(
                DimColumn.string("maintain_user", 64),
                DimColumn.datetime("maintain_time")));
        return Map.copyOf(m);
    }
}
