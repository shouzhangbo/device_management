package com.example.device.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.device.core.applier.ApplyCommand;
import com.example.device.core.applier.ApplyOutcome;
import com.example.device.core.applier.ColumnBinding;
import com.example.device.core.applier.DeviceInfoApplier;
import com.example.device.core.applier.OptimisticConflictException;
import com.example.device.core.auth.DimAuthGuard;
import com.example.device.core.contract.EnvelopeParser;
import com.example.device.core.contract.EnvelopeValidator;
import com.example.device.core.contract.MessageEnvelope;
import com.example.device.core.contract.ParseResult;
import com.example.device.core.contract.ValidatedCommand;
import com.example.device.core.contract.ValidateResult;
import com.example.device.core.dim.DimType;
import com.example.device.core.dim.MsgType;
import com.example.device.core.dto.DeadLetterReplayResult;
import com.example.device.core.dto.PageData;
import com.example.device.core.entity.MqDeadLetterDO;
import com.example.device.core.mapper.MqDeadLetterMapper;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 死信列表/详情/重放/丢弃的边界：源过滤透传、深翻页上限、不存在 4040、已处理 4093、坏内容 4002。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class DeadLetterServiceTest {

    private final MqDeadLetterMapper mapper = mock(MqDeadLetterMapper.class);
    private final EnvelopeParser parser = mock(EnvelopeParser.class);
    private final EnvelopeValidator validator = mock(EnvelopeValidator.class);
    private final DimAuthGuard authGuard = mock(DimAuthGuard.class);
    private final DeviceInfoApplier applier = mock(DeviceInfoApplier.class);

    private final DeadLetterService service =
            new DeadLetterService(mapper, parser, validator, authGuard, applier);

    @Test
    void pagePassesTrimmedSourceAndSkipsQueriesOnEmptyTotal() {
        when(mapper.countByStatus(0, "SRC_A")).thenReturn(0L);

        PageData<?> page = service.page(0, "  SRC_A  ", 1, 20, 1000);

        assertEquals(0L, page.total());
        verify(mapper).countByStatus(0, "SRC_A");
        verify(mapper, never()).selectPaged(anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void pageTreatsBlankSourceAsNoFilter() {
        when(mapper.countByStatus(null, null)).thenReturn(0L);

        service.page(null, "   ", 1, 20, 1000);

        verify(mapper).countByStatus(isNull(), isNull());
    }

    @Test
    void pageRejectsOffsetBeyondMax() {
        BizException thrown = assertThrows(BizException.class,
                () -> service.page(null, null, 12, 100, 1000));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void detailOfUnknownIdIsNotFound() {
        when(mapper.selectById(9L)).thenReturn(null);

        BizException thrown = assertThrows(BizException.class, () -> service.detail(9L));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, thrown.errorCode());
    }

    @Test
    void replayOfUnknownIdIsNotFoundAndNeverClaims() {
        when(mapper.selectById(9L)).thenReturn(null);

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, thrown.errorCode());
        verify(mapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void replayOfHandledRowIsConflict() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), eq("zhangsan"), any())).thenReturn(0);

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.DEAD_LETTER_ALREADY_HANDLED, thrown.errorCode());
        verifyNoInteractions(parser, applier);
    }

    @Test
    void replayOfUnparsableContentIsBadRequest() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("not-json"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), anyString(), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.fail(ParseResult.Status.NOT_JSON, "bad"));

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(validator, authGuard, applier);
    }

    @Test
    void replayOfRejectedPayloadIsBadRequest() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{\"sn\":\"SN-1\"}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), anyString(), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.ok(envelope()));
        when(validator.validate(any())).thenReturn(ValidateResult.invalid("unknown column"));

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.PARAM_FORMAT_ERROR, thrown.errorCode());
        verifyNoInteractions(authGuard, applier);
    }

    @Test
    void replayAppliesThroughSharedChainAndReportsSuccess() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{\"sn\":\"SN-1\"}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), eq("zhangsan"), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.ok(envelope()));
        when(validator.validate(any())).thenReturn(ValidateResult.ok(command()));
        when(applier.apply(any())).thenReturn(ApplyOutcome.APPLIED);

        DeadLetterReplayResult result = service.replay(9L, "zhangsan");

        assertEquals(DeadLetterStatus.REPLAYED.code(), result.status().intValue());
        assertEquals(DeadLetterReplayResult.APPLIED, result.applyResult());
        assertEquals(1, result.recordResult().intValue());
        verify(authGuard).checkOrThrow(DimType.FACTORY, "SRC_A");
        ArgumentCaptor<ApplyCommand> captor = ArgumentCaptor.forClass(ApplyCommand.class);
        verify(applier).apply(captor.capture());
        assertEquals("device-topic", captor.getValue().topic());
        assertEquals("M-1", captor.getValue().msgId());
    }

    @Test
    void replayOfMissingDeviceRecordsResultThree() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{\"sn\":\"SN-1\"}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), anyString(), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.ok(envelope()));
        when(validator.validate(any())).thenReturn(ValidateResult.ok(command()));
        when(applier.apply(any())).thenReturn(ApplyOutcome.MISSING_DEVICE_RECORDED);

        DeadLetterReplayResult result = service.replay(9L, "zhangsan");

        assertEquals(DeadLetterReplayResult.DEVICE_NOT_FOUND, result.applyResult());
        assertEquals(3, result.recordResult().intValue());
    }

    @Test
    void replayHittingIdempotentRecordIsConflict() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{\"sn\":\"SN-1\"}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), anyString(), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.ok(envelope()));
        when(validator.validate(any())).thenReturn(ValidateResult.ok(command()));
        when(applier.apply(any())).thenThrow(new DuplicateKeyException("uk_msg"));

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.REPLAY_HIT_IDEMPOTENT, thrown.errorCode());
    }

    @Test
    void replayLosingCasRaceIsConflict() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{\"sn\":\"SN-1\"}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(1), anyString(), any())).thenReturn(1);
        when(parser.parse(any())).thenReturn(ParseResult.ok(envelope()));
        when(validator.validate(any())).thenReturn(ValidateResult.ok(command()));
        when(applier.apply(any())).thenThrow(new OptimisticConflictException("SN-1", 3));

        BizException thrown = assertThrows(BizException.class, () -> service.replay(9L, "zhangsan"));

        assertEquals(ErrorCode.VERSION_CONFLICT, thrown.errorCode());
    }

    @Test
    void discardOfUnknownIdIsNotFound() {
        when(mapper.selectById(9L)).thenReturn(null);

        BizException thrown = assertThrows(BizException.class, () -> service.discard(9L, "zhangsan"));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, thrown.errorCode());
        verify(mapper, never()).casUpdateStatus(anyLong(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void discardOfHandledRowIsConflict() {
        when(mapper.selectById(9L)).thenReturn(pendingRow("{}"));
        when(mapper.casUpdateStatus(eq(9L), eq(0), eq(2), anyString(), any())).thenReturn(0);

        BizException thrown = assertThrows(BizException.class, () -> service.discard(9L, "zhangsan"));

        assertEquals(ErrorCode.DEAD_LETTER_ALREADY_HANDLED, thrown.errorCode());
    }

    @Test
    void handleTimeSentinelBecomesNullInDetail() {
        MqDeadLetterDO row = pendingRow("{}");
        row.setHandleTime(com.example.device.core.support.TimeValues.SENTINEL);
        when(mapper.selectById(9L)).thenReturn(row);

        assertNull(service.detail(9L).handleTime());
    }

    private static MqDeadLetterDO pendingRow(String content) {
        MqDeadLetterDO row = new MqDeadLetterDO();
        row.setId(9L);
        row.setMsgId("M-1");
        row.setSource("SRC_A");
        row.setTopic("device-topic");
        row.setPartitionNo(0);
        row.setOffsetNo(1L);
        row.setContent(content);
        row.setStatus(DeadLetterStatus.PENDING.code());
        row.setRetryCount(3);
        return row;
    }

    private static MessageEnvelope envelope() {
        return new MessageEnvelope("SRC_A", "M-1", "FACTORY_REGISTER", "SN-1", Map.of(), null, "{}");
    }

    private static ValidatedCommand command() {
        return new ValidatedCommand("SN-1", "SRC_A", "M-1", MsgType.FACTORY_REGISTER, DimType.FACTORY,
                List.of(new ColumnBinding("factory_code", "F-1")), null);
    }
}
