package com.example.device.admin.dto;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EP-005 档案编辑入参（契约 {@code DeviceProfileUpdateCommand}，{@code additionalProperties: false}）。
 * <p>
 * {@code PUT} 是全量替换 PROFILE 维度：未传的四列写成空串，{@code version} 必填。
 * 未知字段与建档同样显式拒绝——尤其要挡住 {@code online_status} 这类外部维度列。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@lombok.Getter
@lombok.Setter
public class DeviceProfileUpdateRequest {

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

    /** 乐观锁版本，取自列表项；不匹配 → {@code 409/4091}。 */
    @NotNull
    @Min(0)
    private Integer version;

    /** 契约外字段。 */
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
