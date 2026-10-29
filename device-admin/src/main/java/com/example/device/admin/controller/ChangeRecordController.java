package com.example.device.admin.controller;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.web.RequestRules;
import com.example.device.core.dto.ChangeRecordItem;
import com.example.device.core.dto.PageData;
import com.example.device.core.service.DeviceAdminService;
import com.example.device.core.web.ApiEnvelope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备变更流水查询（EP-010）。
 * <p>
 * 设备不存在或已逻辑删除 → {@code 404/4040}；存在但无流水 → {@code 200 + items: []}。
 * 这两种语义由 {@link DeviceAdminService#changeRecords} 持有，入口不重复判断。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@RestController
public class ChangeRecordController {

    private final DeviceAdminService adminService;
    private final AdminProperties adminProperties;

    /**
     * @param adminService    后台读侧
     * @param adminProperties 深翻页上限
     */
    public ChangeRecordController(DeviceAdminService adminService, AdminProperties adminProperties) {
        this.adminService = adminService;
        this.adminProperties = adminProperties;
    }

    /**
     * @param sn       设备序列号
     * @param page     页码，从 1 起
     * @param pageSize 每页条数
     * @return 按 {@code create_time} 倒序的分页流水
     */
    @GetMapping("/api/v1/admin/devices/{sn}/change-records")
    public ApiEnvelope<PageData<ChangeRecordItem>> records(
            @PathVariable("sn") String sn,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        RequestRules.requireSn(sn);
        RequestRules.requirePaging(page, pageSize);
        return ApiEnvelope.success(adminService.changeRecords(sn, page, pageSize,
                adminProperties.maxOffsetOrDefault()));
    }
}
