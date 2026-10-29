package com.example.device.core.query;

import com.example.device.core.cache.DeviceCacheValue;
import com.example.device.core.dto.DeviceAdminItem;
import com.example.device.core.dto.DevicePublicView;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.support.TimeValues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 可见列的<b>唯一</b>装配点：主档行 → 缓存 Value / 对外 DTO / 后台 DTO。
 * <p>
 * {@code tech-design} §9.4 的不变式「缓存 Value 字段集 = 对外 DTO 字段集 ∪ {@code update_time}」
 * 只有在<b>单点装配</b>时才成立，因此禁止在 Controller 里再裁一层字段（两处裁剪必然漂移）。
 * 哨兵时间在这里转 {@code null}：若在 Mapper 的 {@code ResultSet} 映射层转，
 * 流水 {@code before_json} 会与库里的真实值不一致，对账时看不出差异。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class PublicAssembler {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 用于把 {@code ext_json} 文本还原成 JSON 结构（后台可见）
     */
    public PublicAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 主档行 → 缓存 Value（16 个 key）。
     *
     * @param row 主档行
     * @return 缓存值；{@code maintain_* / ext_json / version / deleted / id} 一律不带
     */
    public DeviceCacheValue toCacheValue(DeviceInfoDO row) {
        return new DeviceCacheValue(row.getSn(), row.getModelCode(), row.getModelName(),
                row.getFactoryCode(), row.getFactoryName(), TimeValues.nullIfSentinel(row.getProduceTime()),
                row.getRegisterStatus(), row.getOnlineStatus(), row.getBindStatus(),
                row.getBindTarget(), TimeValues.nullIfSentinel(row.getBindTime()),
                row.getRecoverStatus(), TimeValues.nullIfSentinel(row.getScrapTime()),
                row.getRemark(), row.getImageUrl(), row.getUpdateTime());
    }

    /**
     * 缓存 Value → 对外 DTO（15 字段）。
     *
     * @param value 缓存值
     * @return 对外视图
     */
    public DevicePublicView fromCache(DeviceCacheValue value) {
        return value.toPublicView();
    }

    /**
     * 主档行 → 后台 DTO（23 字段，含 {@code version}：后台编辑与删除的乐观锁入参）。
     *
     * @param row 主档行
     * @return 后台视图
     */
    public DeviceAdminItem toAdminItem(DeviceInfoDO row) {
        return new DeviceAdminItem(row.getSn(), row.getModelCode(), row.getModelName(),
                row.getFactoryCode(), row.getFactoryName(), TimeValues.nullIfSentinel(row.getProduceTime()),
                row.getRegisterStatus(), row.getOnlineStatus(), row.getBindStatus(),
                row.getBindTarget(), TimeValues.nullIfSentinel(row.getBindTime()),
                row.getRecoverStatus(), TimeValues.nullIfSentinel(row.getScrapTime()),
                row.getRemark(), row.getImageUrl(), row.getId(), row.getVersion(), row.getDeleted(),
                row.getCreateTime(), row.getUpdateTime(), row.getMaintainUser(),
                TimeValues.nullIfSentinel(row.getMaintainTime()), parseExt(row.getExtJson()));
    }

    /**
     * {@code ext_json} 列文本 → JSON 结构。
     * <p>
     * 该列是 MySQL {@code JSON} 类型，写进去的必然是合法 JSON；解析失败只可能是历史脏数据，
     * 此时返回 {@code null} 而不是让整页后台列表 500。
     * </p>
     *
     * @param text 列文本，允许为空
     * @return JSON 结构，无内容或不可解析时为 {@code null}
     */
    private JsonNode parseExt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
