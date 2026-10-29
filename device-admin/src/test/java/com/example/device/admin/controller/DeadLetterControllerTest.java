package com.example.device.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.device.admin.config.AdminProperties;
import com.example.device.admin.dto.DeadLetterDiscardRequest;
import com.example.device.admin.security.OperatorContext;
import com.example.device.core.dto.DeadLetterDetail;
import com.example.device.core.dto.DeadLetterItem;
import com.example.device.core.dto.DeadLetterReplayResult;
import com.example.device.core.dto.PageData;
import com.example.device.core.service.DeadLetterService;
import com.example.device.core.service.DeadLetterStatus;
import com.example.device.core.web.ApiEnvelope;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 死信入口：默认只查待处理、状态/分页/来源长度在进服务前拒绝、重放取 JWT 操作人、丢弃返回状态 2。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class DeadLetterControllerTest {

    private final DeadLetterService deadLetterService = mock(DeadLetterService.class);
    private final OperatorContext operatorContext = mock(OperatorContext.class);
    private final AdminProperties properties = new AdminProperties(null, null);

    private final DeadLetterController controller =
            new DeadLetterController(deadLetterService, operatorContext, properties);

    @Test
    void listDefaultsToPendingAndUsesEffectiveMaxOffset() {
        when(deadLetterService.page(0, null, 1, 20, AdminProperties.DEFAULT_MAX_OFFSET))
                .thenReturn(new PageData<DeadLetterItem>(List.of(), 0L, 1, 20));

        ApiEnvelope<PageData<DeadLetterItem>> envelope = controller.list(0, null, 1, 20);

        assertEquals(ErrorCode.SUCCESS_CODE, envelope.code());
        verify(deadLetterService).page(0, null, 1, 20, AdminProperties.DEFAULT_MAX_OFFSET);
    }

    @Test
    void listRejectsOutOfContractStatusPagingAndSource() {
        BizException status = assertThrows(BizException.class, () -> controller.list(3, null, 1, 20));
        BizException page = assertThrows(BizException.class, () -> controller.list(0, null, 0, 20));
        BizException pageSize = assertThrows(BizException.class, () -> controller.list(0, null, 1, 0));
        BizException source = assertThrows(BizException.class,
                () -> controller.list(0, "S".repeat(65), 1, 20));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, status.errorCode());
        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, page.errorCode());
        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, pageSize.errorCode());
        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, source.errorCode());
        verifyNoInteractions(deadLetterService);
    }

    @Test
    void detailReturnsServiceResult() {
        DeadLetterDetail detail = new DeadLetterDetail(9L, "M-1", "SRC_A", "topic", "group", 0, 1L, "SN-1",
                "fail", 3, 0, "", null, null, "{}");
        when(deadLetterService.detail(9L)).thenReturn(detail);

        ApiEnvelope<DeadLetterDetail> envelope = controller.detail(9L);

        assertEquals(detail, envelope.data());
    }

    @Test
    void replayUsesAuthenticatedOperator() {
        when(operatorContext.current()).thenReturn("zhangsan");
        DeadLetterReplayResult result = new DeadLetterReplayResult(9L, 1, DeadLetterReplayResult.APPLIED, 1);
        when(deadLetterService.replay(9L, "zhangsan")).thenReturn(result);

        ApiEnvelope<DeadLetterReplayResult> envelope = controller.replay(9L);

        assertEquals(result, envelope.data());
        verify(deadLetterService).replay(9L, "zhangsan");
    }

    @Test
    void discardReturnsDiscardedStatus() {
        when(operatorContext.current()).thenReturn("zhangsan");

        ApiEnvelope<DeadLetterController.DiscardResult> envelope =
                controller.discard(9L, new DeadLetterDiscardRequest("duplicate delivery"));

        assertEquals(9L, envelope.data().id());
        assertEquals(DeadLetterStatus.DISCARDED.code(), envelope.data().status());
        verify(deadLetterService).discard(9L, "zhangsan");
    }

    @Test
    void serviceErrorsPropagateWithTheirCodes() {
        when(operatorContext.current()).thenReturn("zhangsan");
        when(deadLetterService.replay(anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.DEAD_LETTER_ALREADY_HANDLED));
        when(deadLetterService.detail(9L)).thenThrow(new BizException(ErrorCode.RESOURCE_NOT_FOUND));

        BizException replay = assertThrows(BizException.class, () -> controller.replay(9L));
        BizException detail = assertThrows(BizException.class, () -> controller.detail(9L));

        assertEquals(ErrorCode.DEAD_LETTER_ALREADY_HANDLED, replay.errorCode());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, detail.errorCode());
    }
}
