package com.example.device.core.service;

/**
 * 后台写命令（契约 {@code DeviceCreateCommand} / {@code DeviceProfileUpdateCommand} 的内部形态）。
 * <p>
 * <b>入参面只有 PROFILE 的 4 列（建档再加 {@code sn}）与 {@code version}</b>：
 * 状态列、时间列、{@code maintain_*}、{@code ext_json} 一律由服务端写，
 * 请求体不接受（{@code tech-design} §5.2 EP-004/EP-005）。因此本类型<b>无法</b>被构造出越界字段，
 * 契约层的 {@code additionalProperties: false} 只是第二道防线。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public final class AdminCommands {

    private AdminCommands() {
    }

    /**
     * 建档命令：只给档案列，状态列全部取 DDL 默认值。
     *
     * @param sn        设备序列号
     * @param modelCode 型号编码，可为 {@code null}
     * @param modelName 型号名称，可为 {@code null}
     * @param remark    备注，可为 {@code null}
     * @param imageUrl  图片 URL，可为 {@code null}
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record Create(String sn, String modelCode, String modelName, String remark, String imageUrl) {
    }

    /**
     * 档案编辑命令。
     * <p>
     * {@code PUT} 是<b>全量替换该维度</b>，不是替换整行：未传的 PROFILE 列要置成默认值，
     * 其余维度列一律不受影响（契约 {@code DeviceProfileUpdateCommand} 描述行）。
     * </p>
     *
     * @param modelCode 型号编码，可为 {@code null}（视为清空）
     * @param modelName 型号名称，可为 {@code null}
     * @param remark    备注，可为 {@code null}
     * @param imageUrl  图片 URL，可为 {@code null}
     * @param version   乐观锁版本，取自列表项；不匹配 → {@code 409/4091}
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record ProfileUpdate(String modelCode, String modelName, String remark, String imageUrl,
                                Integer version) {
    }
}
