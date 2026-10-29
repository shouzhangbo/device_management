package com.example.device.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/**
 * 后台列表/详情项：契约 {@code DeviceAdminItem} 用 {@code allOf} 把对外 15 字段与 8 个内部字段
 * <b>拍平在同一层</b>，因此本 record 必须平铺 23 个组件，不能嵌套 {@link DevicePublicView}
 * （嵌套会让响应多出一个 {@code public_view} 包装键，直接违反契约）。
 * <p>
 * 与对外视图的差异<b>只允许"多给"不允许"少给"</b>；其中 {@code version} 是 EP-005/EP-009 乐观锁入参的来源。
 * </p>
 *
 * @param sn             设备序列号
 * @param modelCode      型号编码
 * @param modelName      型号名称
 * @param factoryCode    工厂编码
 * @param factoryName    工厂名称
 * @param produceTime    生产时间，未同步为 {@code null}
 * @param registerStatus 出厂注册状态
 * @param onlineStatus   在线状态
 * @param bindStatus     绑定状态
 * @param bindTarget     绑定目标
 * @param bindTime       绑定时间，未同步为 {@code null}
 * @param recoverStatus  回收报废状态
 * @param scrapTime      报废时间，未同步为 {@code null}
 * @param remark         备注
 * @param imageUrl       设备图片 URL
 * @param id             主键，不对外
 * @param version        乐观锁版本
 * @param deleted        逻辑删除标记：0-正常 1-已删除
 * @param createTime     创建时间
 * @param updateTime     更新时间，同时是缓存对账水位
 * @param maintainUser   最近维护人（拼音账号），不对外、不进缓存
 * @param maintainTime   最近维护时间，不对外、不进缓存
 * @param extJson        外部透传附加信息，后台可见、不对外
 * @author houzhangbo
 * @since 2026-10-29
 */
public record DeviceAdminItem(
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
        Long id,
        Integer version,
        Integer deleted,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String maintainUser,
        LocalDateTime maintainTime,
        JsonNode extJson) {

    /** 后台可见字段总数：对外 15 + 内部 8。 */
    public static final int FIELD_COUNT = 23;

    /**
     * 取出对外 15 字段视图，供缓存与对外响应复用同一份裁剪结果。
     *
     * @return 对外 DTO
     */
    public DevicePublicView toPublicView() {
        return new DevicePublicView(sn, modelCode, modelName, factoryCode, factoryName, produceTime,
                registerStatus, onlineStatus, bindStatus, bindTarget, bindTime, recoverStatus, scrapTime,
                remark, imageUrl);
    }
}
