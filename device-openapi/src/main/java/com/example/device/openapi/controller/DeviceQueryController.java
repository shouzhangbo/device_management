package com.example.device.openapi.controller;

import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.dto.DevicePublicView;
import com.example.device.core.query.BatchQueryResult;
import com.example.device.core.query.DeviceInfoQueryService;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.example.device.openapi.dto.BatchQueryRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外查询入口（EP-001 / EP-002）：只做参数校验、调用读服务、包统一外壳。
 * <p>
 * 本类没有任何写路径：逻辑删除与"从未存在"都由 {@link DeviceInfoQueryService} 合并为
 * {@link Optional#empty()}，对外统一 {@code 404/4040}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestController
public class DeviceQueryController {

    /** 404 的稳定短语，与契约示例同串。 */
    private static final String DEVICE_NOT_FOUND = "device not found";

    private final DeviceInfoQueryService queryService;

    /**
     * @param queryService 读路径唯一入口
     */
    public DeviceQueryController(DeviceInfoQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 单查（EP-001）。
     *
     * @param sn 设备序列号
     * @return 200 + 对外 15 字段；不存在或已逻辑删除为 404/4040
     */
    @GetMapping("/api/v1/devices/{sn}")
    public ResponseEntity<ApiEnvelope<DevicePublicView>> one(@PathVariable("sn") String sn) {
        requireSn(sn);
        return queryService.findBySn(sn)
                .map(view -> ResponseEntity.ok(ApiEnvelope.success(view)))
                .orElseGet(() -> ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
                        .body(ApiEnvelope.error(ErrorCode.RESOURCE_NOT_FOUND, DEVICE_NOT_FOUND)));
    }

    /**
     * 批量查（EP-002）：部分命中仍返回 200，缺失项在 {@code missing} 中标出。
     *
     * @param request 请求体
     * @return 200 + 逐项结果
     */
    @PostMapping("/api/v1/devices/batch")
    public ApiEnvelope<BatchQueryResult> batch(@Valid @RequestBody BatchQueryRequest request) {
        return ApiEnvelope.success(queryService.findBatch(request.sns()));
    }

    private static void requireSn(String sn) {
        if (sn == null || sn.length() > PayloadLimits.MAX_SN_LENGTH || !sn.matches(PayloadLimits.SN_PATTERN)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "sn format error");
        }
    }
}
