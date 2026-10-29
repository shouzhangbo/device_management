package com.example.device.core.dim;

/**
 * 外部 Kafka 消息的业务类型（信封 {@code msg_type} 列取值）。
 * <p>
 * 未知 {@code msg_type} 在校验阶段被拒绝并正常 ack（{@code tech-design} R11：未知类型卡分区会导致整个 Topic 积压），
 * 因此本枚举<b>不包含</b>"未知"这一取值，解析结果用 {@link java.util.Optional} 表达。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum MsgType {

    /** 出厂注册：SN 不存在时建档。 */
    FACTORY_REGISTER,

    /** 在线状态变更。 */
    ONLINE_CHANGE,

    /** 绑定/解绑变更。 */
    BIND_CHANGE,

    /** 回收/报废结果。 */
    RECOVER_CHANGE,

    /** 档案变更（默认不授权外部来源，见 {@code data-model} §6.5）。 */
    PROFILE_CHANGE
}
