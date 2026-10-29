package com.example.device.core.applier;

import com.example.device.core.dim.DimSpec;
import com.example.device.core.dim.DimType;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一次维度写入的完整意图，{@link DeviceInfoApplier} 的唯一入参。
 * <p>
 * 消息侧与后台侧共用本类型：后台档案编辑把 {@code PROFILE} 与 {@code MAINTAIN} 的列放在同一次调用里，
 * 因此是<b>一次 CAS、一条流水</b>（{@code data-model} §8.4 硬规则 3：拆成两次 CAS 会让第二次必然版本冲突）。
 * </p>
 *
 * @param sn              设备序列号
 * @param dim             流水记录的维度；逻辑删除时为 {@code null}（流水 {@code dim_type} 写空串）
 * @param msgType         落到流水 {@code msg_type} 的原始值：消息类型或 {@code ADMIN_*} 常量
 * @param source          来源编码，后台固定 {@code ADMIN}
 * @param msgId           消息 ID，后台填本地生成的 {@code ADM-<uuid>}
 * @param columns         本次要写的列，必须落在 {@code dim} 的白名单内（允许额外携带 MAINTAIN 列）
 * @param extJson         外部透传附加信息的原始 JSON 文本，允许为 {@code null}（表示不改该列）
 * @param createIfAbsent  SN 不存在时是否建档（只有 {@code FACTORY_REGISTER} 与后台建档为 true）
 * @param expectedVersion 后台传入的乐观锁版本；{@code null} 表示由 Applier 先读当前版本再 CAS
 * @param opUser          操作人拼音账号；消息来源时为空串
 * @param topic           来源 Topic，幂等记录留痕用；后台为空串
 * @author houzhangbo
 * @since 2026-10-29
 */
public record ApplyCommand(
        String sn,
        DimType dim,
        String msgType,
        String source,
        String msgId,
        List<ColumnBinding> columns,
        String extJson,
        boolean createIfAbsent,
        Integer expectedVersion,
        String opUser,
        String topic) {

    /**
     * 规范化视图：{@code null} 列清单视为空清单，避免调用方处处判空。
     *
     * @return 不可为 {@code null} 的列清单
     */
    public List<ColumnBinding> columnsOrEmpty() {
        return columns == null ? List.of() : columns;
    }

    /**
     * 流水 {@code dim_type} 取值：无维度时写空串而不是新造枚举值。
     *
     * @return 维度编码
     */
    public String dimTypeCode() {
        return dim == null ? DimSpec.NO_DIM : dim.name();
    }

    /**
     * 幂等记录的 {@code biz_type}，本域固定 device。
     *
     * @return 业务类型
     */
    public String bizType() {
        return "device";
    }

    /**
     * 本次命令实际触及的维度集合，流水 {@code before_json} 的快照范围。
     * <p>
     * 后台档案编辑把 {@code PROFILE} 与 {@code MAINTAIN} 的列合在同一次 CAS 里，
     * 光看 {@link #dim()} 会漏掉 MAINTAIN 列的变更前值，因此按列反查维度。
     * </p>
     *
     * @return 涉及维度，保持声明顺序
     */
    public List<DimType> dimsInvolved() {
        LinkedHashSet<DimType> dims = new LinkedHashSet<>();
        if (dim != null) {
            dims.add(dim);
        }
        for (ColumnBinding c : columnsOrEmpty()) {
            for (DimType d : DimType.values()) {
                if (DimSpec.allows(d, c.column())) {
                    dims.add(d);
                }
            }
        }
        return List.copyOf(dims);
    }
}
