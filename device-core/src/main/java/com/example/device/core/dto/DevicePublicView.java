package com.example.device.core.dto;

import java.time.LocalDateTime;

/**
 * 对外设备视图：契约 {@code components.schemas.Device} 的 15 个字段，一个不多一个不少。
 * <p>
 * 不含 {@code id / version / deleted / create_time / update_time / maintain_user / maintain_time / ext_json}
 * （{@code tech-design} §9.5 可见性表）。三个时间字段为 {@code null} 表示外部尚未同步，
 * 由装配层把库里的哨兵值 {@code 1970-01-01 00:00:00} 转换而来，禁止让哨兵值泄漏到外部。
 * </p>
 *
 * @param sn             设备序列号
 * @param modelCode      型号编码
 * @param modelName      型号名称
 * @param factoryCode    工厂编码
 * @param factoryName    工厂名称
 * @param produceTime    生产时间，未同步为 {@code null}
 * @param registerStatus 出厂注册状态：0-未注册 1-已注册
 * @param onlineStatus   在线状态：0-未知 1-在线 2-离线
 * @param bindStatus     绑定状态：0-未绑定 1-已绑定
 * @param bindTarget     绑定目标，解绑后为空串
 * @param bindTime       绑定时间，未同步为 {@code null}
 * @param recoverStatus  回收报废状态：0-正常 1-已回收 2-已报废
 * @param scrapTime      报废时间，未同步为 {@code null}
 * @param remark         备注
 * @param imageUrl       设备图片 URL
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DevicePublicView(
        String sn,
        String modelCode,
        String modelName,
        String factoryCode,
        String factoryName,
        LocalDateTime produceTime,
        Integer registerStatus,
        Integer onlineStatus,
        Integer bindStatus,
        String bindTarget,
        LocalDateTime bindTime,
        Integer recoverStatus,
        LocalDateTime scrapTime,
        String remark,
        String imageUrl) {

    /** 契约 {@code Device} 的 required 字段数，CI 断言用。 */
    public static final int FIELD_COUNT = 15;
}
