package com.example.device.openapi.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.device.core.dto.DevicePublicView;
import com.example.device.core.query.BatchQueryResult;
import com.example.device.core.query.DeviceInfoQueryService;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 对外查询控制器的映射：命中 200、缺失 404/4040、非法 SN 400/4002、批量原样返回。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class DeviceQueryControllerTest {

    private final DeviceInfoQueryService queryService = mock(DeviceInfoQueryService.class);

    private final DeviceQueryController controller = new DeviceQueryController(queryService);

    @Test
    void existingDeviceReturnsSuccessEnvelope() {
        DevicePublicView view = view("SN-1");
        when(queryService.findBySn("SN-1")).thenReturn(Optional.of(view));

        ResponseEntity<ApiEnvelope<DevicePublicView>> response = controller.one("SN-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ErrorCode.SUCCESS_CODE, response.getBody().code());
        assertSame(view, response.getBody().data());
    }

    @Test
    void missingOrDeletedDeviceIsUniform404() {
        when(queryService.findBySn("SN-1")).thenReturn(Optional.empty());

        ResponseEntity<ApiEnvelope<DevicePublicView>> response = controller.one("SN-1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND.code(), response.getBody().code());
        assertEquals("device not found", response.getBody().message());
        assertNull(response.getBody().data());
    }

    @Test
    void illegalSnIsRejectedBeforeQueryingTheService() {
        BizException thrown = assertThrows(BizException.class, () -> controller.one("SN 1"));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        assertThrows(BizException.class, () -> controller.one("S".repeat(65)));
        assertThrows(BizException.class, () -> controller.one("SN/1"));
    }

    @Test
    void batchReturnsItemsFromTheService() {
        BatchQueryResult result = new BatchQueryResult(
                List.of(new BatchQueryResult.Item("SN-1", true, view("SN-1")),
                        new BatchQueryResult.Item("SN-2", false, null)),
                List.of("SN-2"));
        when(queryService.findBatch(List.of("SN-1", "SN-2"))).thenReturn(result);

        ApiEnvelope<BatchQueryResult> envelope = controller.batch(new com.example.device.openapi.dto.BatchQueryRequest(
                List.of("SN-1", "SN-2")));

        assertEquals(ErrorCode.SUCCESS_CODE, envelope.code());
        assertSame(result, envelope.data());
    }

    private static DevicePublicView view(String sn) {
        return new DevicePublicView(sn, "M-1", "model", "F-1", "factory", null,
                1, 1, 0, "", null, 0, null, "", "");
    }
}
