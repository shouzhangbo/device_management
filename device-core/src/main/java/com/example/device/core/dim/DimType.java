package com.example.device.core.dim;

/**
 * 设备信息的写入维度。
 * <p>
 * 一条消息只碰一个维度，维度之间永不互相覆盖（{@code tech-design} R10）。
 * 枚举值与 {@code data-model} §8.4 的 {@code dim_type} 列取值逐一对应。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
public enum DimType {

    /** 出厂注册维度：工厂编码/名称、生产时间、注册状态。 */
    FACTORY,

    /** 在线状态维度。 */
    ONLINE,

    /** 绑定维度：状态、目标、绑定时间。 */
    BIND,

    /** 回收/报废维度。 */
    RECOVER,

    /** 档案维度：型号、备注、图片。 */
    PROFILE,

    /** 维护人维度：仅后台可写，不进缓存、不对外。 */
    MAINTAIN
}
