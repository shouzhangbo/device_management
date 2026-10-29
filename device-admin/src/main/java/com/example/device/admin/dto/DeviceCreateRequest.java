package com.example.device.admin.dto;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EP-004 建档入参（契约 {@code DeviceCreateCommand}，{@code additionalProperties: false}）。
 * <p>
 * 只接受 {@code sn} 与 PROFILE 四列；状态列、时间列、{@code maintain_*}、{@code ext_json}
 * 一律不接受（{@code tech-design} §5.2 EP-004）。未知字段被 {@link JsonAnySetter} 收集后显式拒绝：
 * 全局 Jackson 关闭了"未知字段报错"，若不再兜一层，多传的 {@code online_status} 会被静默丢弃，
 * 调用方却以为写成功——这正是"后台与外部权威源争同一列"的入口。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class DeviceCreateRequest {

    /** 设备序列号。 */
    @NotBlank
    @Size(max = PayloadLimits.MAX_SN_LENGTH)
    @Pattern(regexp = PayloadLimits.SN_PATTERN)
    private String sn;

    /** 型号编码。 */
    @Size(max = PayloadLimits.MAX_MODEL_CODE_LENGTH)
    private String modelCode;

    /** 型号名称。 */
    @Size(max = PayloadLimits.MAX_MODEL_NAME_LENGTH)
    private String modelName;

    /** 备注。 */
    @Size(max = PayloadLimits.MAX_REMARK_LENGTH)
    private String remark;

    /** 设备图片 URL。 */
    @Size(max = PayloadLimits.MAX_IMAGE_URL_LENGTH)
    private String imageUrl;

    /** 契约外字段：收集后整体拒绝，不静默丢弃。 */
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    /**
     * Jackson 未知字段收集。
     *
     * @param name  字段名
     * @param value 字段值
     */
    @JsonAnySetter
    public void captureUnknown(String name, Object value) {
        unknownFields.put(name, value);
    }

    /**
     * 契约外字段校验。
     *
     * @throws BizException {@code 400/4002} 带了契约外字段
     */
    public void requireNoUnknownFields() {
        if (!unknownFields.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR,
                    "unknown field(s): " + String.join(", ", unknownFields.keySet()));
        }
    }
}
