package com.example.device.admin.controller;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.dto.DeviceCreateRequest;
import com.example.device.admin.dto.DeviceProfileUpdateRequest;
import com.example.device.admin.security.OperatorContext;
import com.example.device.admin.web.IdempotencyKeyFilter;
import com.example.device.admin.web.RequestRules;
import com.example.device.core.contract.PayloadLimits;
import com.example.device.core.dto.DeviceAdminItem;
import com.example.device.core.dto.PageData;
import com.example.device.core.query.DeviceQueryFilter;
import com.example.device.core.service.AdminCommands;
import com.example.device.core.service.DeviceAdminService;
import com.example.device.core.service.IdempotencyKeyGuard;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台设备主档入口（EP-003 列表 / EP-004 建档 / EP-005 档案编辑 / EP-009 逻辑删）。
 * <p>
 * Controller 只做"绑定 + 契约校验 + 调服务 + 包外壳"：所有写路径都经 {@link DeviceAdminService}
 * 汇入 core 的唯一事务入口，操作人一律取 {@link OperatorContext}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/api/v1/admin/devices")
public class DeviceAdminController {

    private final DeviceAdminService adminService;
    private final IdempotencyKeyGuard idempotencyKeyGuard;
    private final OperatorContext operatorContext;
    private final AdminProperties adminProperties;

    /**
     * @param adminService        后台写侧编排
     * @param idempotencyKeyGuard 建档幂等键
     * @param operatorContext     当前操作人
     * @param adminProperties     深翻页上限
     */
    public DeviceAdminController(DeviceAdminService adminService, IdempotencyKeyGuard idempotencyKeyGuard,
                                 OperatorContext operatorContext, AdminProperties adminProperties) {
        this.adminService = adminService;
        this.idempotencyKeyGuard = idempotencyKeyGuard;
        this.operatorContext = operatorContext;
        this.adminProperties = adminProperties;
    }

    /**
     * 列表（EP-003）：必须带至少一个高区分度条件，否则 {@code 400/4004}。
     *
     * @param page             页码，从 1 起，默认 1
     * @param pageSize         每页条数，默认 20，上限 100
     * @param sn               精确 SN
     * @param snPrefix         SN 右模糊前缀（≥4 字符）
     * @param modelCode        型号编码
     * @param onlineStatus     在线状态 0/1/2，非高区分度条件
     * @param produceTimeStart 生产时间起（含，带时区）
     * @param produceTimeEnd   生产时间止（含，带时区）
     * @return 分页结果（后台 23 列，含 {@code version}）
     */
    @GetMapping
    public ApiEnvelope<PageData<DeviceAdminItem>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "sn", required = false) String sn,
            @RequestParam(name = "sn_prefix", required = false) String snPrefix,
            @RequestParam(name = "model_code", required = false) String modelCode,
            @RequestParam(name = "online_status", required = false) Integer onlineStatus,
            @RequestParam(name = "produce_time_start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime produceTimeStart,
            @RequestParam(name = "produce_time_end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime produceTimeEnd) {
        RequestRules.requirePaging(page, pageSize);
        requireOnlineStatus(onlineStatus);
        requireMaxLength(modelCode, PayloadLimits.MAX_MODEL_CODE_LENGTH, "model_code");
        DeviceQueryFilter filter = new DeviceQueryFilter(sn, snPrefix, modelCode, onlineStatus,
                RequestRules.toLocal(produceTimeStart), RequestRules.toLocal(produceTimeEnd),
                false, page, pageSize, adminProperties.maxOffsetOrDefault());
        return ApiEnvelope.success(adminService.list(filter));
    }

    /**
     * 建档（EP-004）。
     * <p>
     * {@code Idempotency-Key} 由 {@code IdempotencyKeyFilter} 强制；同一 Key 重放返回首次结果，
     * 不再执行第二次写。
     * </p>
     *
     * @param body           建档入参
     * @param idempotencyKey 幂等键
     * @return 201 + {@code {sn, version}}
     */
    @PostMapping
    public ResponseEntity<ApiEnvelope<CreateResult>> create(
            @Valid @RequestBody DeviceCreateRequest body,
            @RequestHeader(IdempotencyKeyFilter.HEADER) String idempotencyKey) {
        body.requireNoUnknownFields();
        AdminCommands.Create command = new AdminCommands.Create(body.getSn(), body.getModelCode(),
                body.getModelName(), body.getRemark(), body.getImageUrl());
        String operator = operatorContext.current();
        DeviceAdminItem created = idempotencyKeyGuard.runIfNew(idempotencyKey, DeviceAdminItem.class,
                () -> adminService.create(command, operator));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiEnvelope.success(new CreateResult(created.sn(), created.version())));
    }

    /**
     * 档案编辑（EP-005）：PROFILE 全量替换 + MAINTAIN 自动写入，一次 CAS。
     *
     * @param sn   设备序列号
     * @param body 编辑入参（含 {@code version}）
     * @return 更新后的主档项（含新 {@code version}）
     */
    @PutMapping("/{sn}")
    public ApiEnvelope<DeviceAdminItem> update(@PathVariable("sn") String sn,
                                               @Valid @RequestBody DeviceProfileUpdateRequest body) {
        RequestRules.requireSn(sn);
        body.requireNoUnknownFields();
        AdminCommands.ProfileUpdate command = new AdminCommands.ProfileUpdate(body.getModelCode(),
                body.getModelName(), body.getRemark(), body.getImageUrl(), body.getVersion());
        return ApiEnvelope.success(adminService.updateProfile(sn, command, operatorContext.current()));
    }

    /**
     * 逻辑删除（EP-009）：成功返回 204 无响应体。
     *
     * @param sn      设备序列号
     * @param version 乐观锁版本
     * @return 204
     */
    @DeleteMapping("/{sn}")
    public ResponseEntity<Void> delete(@PathVariable("sn") String sn,
                                       @RequestParam("version") int version) {
        RequestRules.requireSn(sn);
        if (version < 0) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "version must be >= 0");
        }
        adminService.delete(sn, version, operatorContext.current());
        return ResponseEntity.noContent().build();
    }

    private static void requireOnlineStatus(Integer onlineStatus) {
        if (onlineStatus != null && (onlineStatus < 0 || onlineStatus > 2)) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "online_status must be 0, 1 or 2");
        }
    }

    private static void requireMaxLength(String value, int maxLength, String name) {
        if (value != null && value.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, name + " is too long");
        }
    }

    /**
     * 建档成功的响应体（契约的 {@code data} 只含 {@code sn} 与 {@code version}）。
     *
     * @param sn      设备序列号
     * @param version 初始版本，恒为 0，供随后 PUT 的 CAS 入参
     * @author houzhangbo
     * @since 2026-10-29
     */
    public record CreateResult(String sn, Integer version) {
    }
}
