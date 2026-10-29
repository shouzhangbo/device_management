package com.example.device.core.cache;

import com.example.device.core.dto.DevicePublicView;
import java.time.LocalDateTime;

/**
 * 缓存 Value 的<b>唯一</b>结构：对外 15 个字段拍平 + 对账用的 {@code updateTime}，共 16 个 key。
 * <p>
 * 不变式（{@code tech-design} §9.4、契约 {@code Device} 描述行）：<b>缓存 Value 字段集 = 对外 DTO 字段集 ∪ {update_time}</b>。
 * 本类是这条不变式的唯一落地点，字段增删必须同时改 {@link DevicePublicView}，CI 用键数量断言把守。
 * {@code ext_json / maintain_* / version / deleted / id} 绝不允许出现在本类型（大 Key 与可见性双重红线）。
 * </p>
 *
 * @param sn             设备序列号
 * @param modelCode      型号编码
 * @param modelName      型号名称
 * @param factoryCode    工厂编码
 * @param factoryName    工厂名称
 * @param produceTime    生产时间，哨兵值已转 {@code null}
 * @param registerStatus 出厂注册状态
 * @param onlineStatus   在线状态
 * @param bindStatus     绑定状态
 * @param bindTarget     绑定目标
 * @param bindTime       绑定时间，哨兵值已转 {@code null}
 * @param recoverStatus  回收报废状态
 * @param scrapTime      报废时间，哨兵值已转 {@code null}
 * @param remark         备注
 * @param imageUrl       设备图片 URL
 * @param updateTime     主档更新时间，EP-007 判断缓存是否落后的依据，<b>不对外</b>
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeviceCacheValue(
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
        String imageUrl,
        LocalDateTime updateTime) {

    /** 缓存 Value 的顶层 key 数量：对外 15 个字段 + {@code update_time}。 */
    public static final int KEY_COUNT = 16;

    /**
     * 去掉 {@code update_time} 得到对外视图，保证响应与缓存同源而不是各裁一次。
     *
     * @return 对外 DTO
     */
    public DevicePublicView toPublicView() {
        return new DevicePublicView(sn, modelCode, modelName, factoryCode, factoryName, produceTime,
                registerStatus, onlineStatus, bindStatus, bindTarget, bindTime, recoverStatus, scrapTime,
                remark, imageUrl);
    }
}
