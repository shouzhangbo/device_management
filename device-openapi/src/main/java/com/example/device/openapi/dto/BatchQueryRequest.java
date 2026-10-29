package com.example.device.openapi.dto;

import com.example.device.core.contract.PayloadLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * EP-002 批量查询入参（契约 {@code BatchQueryRequest}）。
 * <p>
 * 条数上限不在这里用 {@code @Size} 表达：上限来自配置 {@code device.query.batch-max}（缺省 100），
 * 由服务层按生效值判定并返回 {@code 400/4003}；用注解写死会让"配置改大、校验没变"两处漂移。
 * 空列表同样交给服务层，返回契约要求的 {@code 4001} 而不是"格式错误"。
 * </p>
 *
 * @param sns 待查询 SN 列表
 * @author houzhangbo
 * @since 2026-10-29
 */
public record BatchQueryRequest(
        @Valid
        List<@NotBlank @Size(max = PayloadLimits.MAX_SN_LENGTH)
                @Pattern(regexp = PayloadLimits.SN_PATTERN) String> sns) {
}
