package com.example.device.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.dto.DeviceCreateRequest;
import com.example.device.admin.dto.DeviceProfileUpdateRequest;
import com.example.device.admin.security.OperatorContext;
import com.example.device.core.dto.DeviceAdminItem;
import com.example.device.core.dto.PageData;
import com.example.device.core.query.DeviceQueryFilter;
import com.example.device.core.service.AdminCommands;
import com.example.device.core.service.DeviceAdminService;
import com.example.device.core.service.IdempotencyKeyGuard;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 后台入口的契约映射：建档 201 + 幂等键、编辑 200、删除 204 空体、非法分页/路径 SN 在进服务前拒绝。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class DeviceAdminControllerTest {

    private static final String KEY = "idem-key-0001";

    private final DeviceAdminService adminService = mock(DeviceAdminService.class);
    private final IdempotencyKeyGuard guard = mock(IdempotencyKeyGuard.class);
    private final OperatorContext operatorContext = mock(OperatorContext.class);
    private final AdminProperties properties = new AdminProperties(null, null);

    private final DeviceAdminController controller =
            new DeviceAdminController(adminService, guard, operatorContext, properties);

    @Test
    @SuppressWarnings("unchecked")
    void createReturns201WithSnAndVersion() {
        when(operatorContext.current()).thenReturn("zhangsan");
        when(guard.runIfNew(eq(KEY), eq(DeviceAdminItem.class), any()))
                .thenAnswer(call -> ((Supplier<DeviceAdminItem>) call.getArgument(2)).get());
        when(adminService.create(any(), eq("zhangsan"))).thenReturn(item("SN-1", 0));

        ResponseEntity<ApiEnvelope<DeviceAdminController.CreateResult>> response =
                controller.create(request("SN-1"), KEY);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("SN-1", response.getBody().data().sn());
        assertEquals(0, response.getBody().data().version().intValue());
        ArgumentCaptor<AdminCommands.Create> captor = ArgumentCaptor.forClass(AdminCommands.Create.class);
        verify(adminService).create(captor.capture(), eq("zhangsan"));
        assertEquals("SN-1", captor.getValue().sn());
    }

    @Test
    void createRejectsUnknownFieldsBeforeTouchingService() {
        DeviceCreateRequest body = request("SN-1");
        body.captureUnknown("online_status", 1);

        BizException thrown = assertThrows(BizException.class, () -> controller.create(body, KEY));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(adminService, guard, operatorContext);
    }

    @Test
    void updateReturnsUpdatedItem() {
        when(operatorContext.current()).thenReturn("zhangsan");
        when(adminService.updateProfile(eq("SN-1"), any(), eq("zhangsan"))).thenReturn(item("SN-1", 3));
        DeviceProfileUpdateRequest body = new DeviceProfileUpdateRequest();
        body.setModelCode("M-1");
        body.setVersion(2);

        ApiEnvelope<DeviceAdminItem> envelope = controller.update("SN-1", body);

        assertEquals(ErrorCode.SUCCESS_CODE, envelope.code());
        assertEquals(3, envelope.data().version().intValue());
    }

    @Test
    void updateRejectsIllegalSn() {
        BizException thrown = assertThrows(BizException.class,
                () -> controller.update("SN 1", new DeviceProfileUpdateRequest()));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(adminService);
    }

    @Test
    void deleteReturns204WithoutBody() {
        when(operatorContext.current()).thenReturn("zhangsan");

        ResponseEntity<Void> response = controller.delete("SN-1", 4);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(adminService).delete("SN-1", 4, "zhangsan");
    }

    @Test
    void deleteRejectsNegativeVersion() {
        BizException thrown = assertThrows(BizException.class, () -> controller.delete("SN-1", -1));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(adminService);
    }

    @Test
    void listPassesEffectiveMaxOffsetToFilter() {
        when(adminService.list(any())).thenReturn(new PageData<>(List.of(item("SN-1", 0)), 1L, 1, 20));

        ApiEnvelope<PageData<DeviceAdminItem>> envelope =
                controller.list(1, 20, "SN-1", null, null, null, null, null);

        assertEquals(1L, envelope.data().total());
        ArgumentCaptor<DeviceQueryFilter> captor = ArgumentCaptor.forClass(DeviceQueryFilter.class);
        verify(adminService).list(captor.capture());
        assertEquals(AdminProperties.DEFAULT_MAX_OFFSET, captor.getValue().maxOffset());
        assertEquals("SN-1", captor.getValue().sn());
    }

    @Test
    void listRejectsOutOfContractPagingAndStatus() {
        BizException pageSize = assertThrows(BizException.class,
                () -> controller.list(1, 101, "SN-1", null, null, null, null, null));
        BizException status = assertThrows(BizException.class,
                () -> controller.list(1, 20, "SN-1", null, null, 3, null, null));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, pageSize.errorCode());
        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, status.errorCode());
        verifyNoInteractions(adminService);
    }

    private static DeviceCreateRequest request(String sn) {
        DeviceCreateRequest request = new DeviceCreateRequest();
        request.setSn(sn);
        request.setModelCode("M-1");
        return request;
    }

    private static DeviceAdminItem item(String sn, int version) {
        return new DeviceAdminItem(sn, "M-1", "model", "F-1", "factory", null,
                1, 1, 0, "", null, 0, null, "", "", 1L, version, 0, null, null, "zhangsan", null, null);
    }
}
