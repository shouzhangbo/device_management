package com.example.device.core.applier;

import com.example.device.core.entity.DeviceInfoChangeRecordDO;
import com.example.device.core.mapper.DeviceInfoChangeRecordMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 变更流水写入封装。
 * <p>
 * {@code result} 只落三个值：1-已更新、3-设备不存在、4-未授权来源
 * （{@code data-model} 附录 B）。重复消息与版本冲突<b>不入本表</b>：
 * 前者被唯一索引判定、整事务回滚，后者触发重试，二者由幂等表与指标承载。
 * 同一事件在两张表里各记一种口径，排障时两处结论会互相矛盾。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class DeviceRecorder {

    /** 已更新。 */
    public static final int RESULT_APPLIED = 1;

    /** 设备不存在。 */
    public static final int RESULT_MISSING_DEVICE = 3;

    /** 未授权来源（保留给"重放时授权已变化"，正常消费路径不产生）。 */
    public static final int RESULT_UNAUTHORIZED_SOURCE = 4;

    private final DeviceInfoChangeRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param recordMapper 流水持久层
     * @param objectMapper 与对外响应同源的 JSON 配置，保证流水里的时间格式不与响应分叉
     */
    public DeviceRecorder(DeviceInfoChangeRecordMapper recordMapper, ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记一条"已更新"流水。
     *
     * @param cmd    本次命令
     * @param before 变更前的本维度快照
     * @param after  变更后的快照
     */
    public void recordApplied(ApplyCommand cmd, Map<String, Object> before, Map<String, Object> after) {
        insert(cmd, RESULT_APPLIED, before, after);
    }

    /**
     * 记一条"设备不存在"流水：主档无行且不建档，事务照常提交、消息照常 ack。
     *
     * @param cmd 本次命令
     */
    public void recordMissing(ApplyCommand cmd) {
        insert(cmd, RESULT_MISSING_DEVICE, Map.of(), DimColumns.snapshotOfBindings(cmd.columnsOrEmpty()));
    }

    /**
     * 记一条"未授权来源"流水。
     *
     * @param cmd 本次命令
     */
    public void recordUnauthorized(ApplyCommand cmd) {
        insert(cmd, RESULT_UNAUTHORIZED_SOURCE, Map.of(), Map.of());
    }

    /**
     * 建档成功后的流水：{@code before} 为空对象（此前库里没有这一行）。
     *
     * @param cmd     本次命令
     * @param created 建档后写入的列
     */
    public void recordCreated(ApplyCommand cmd, List<ColumnBinding> created) {
        insert(cmd, RESULT_APPLIED, Map.of(), DimColumns.snapshotOfBindings(created));
    }

    private void insert(ApplyCommand cmd, int result, Map<String, Object> before, Map<String, Object> after) {
        LocalDateTime now = LocalDateTime.now();
        DeviceInfoChangeRecordDO row = new DeviceInfoChangeRecordDO();
        row.setSn(cmd.sn());
        row.setSource(cmd.source());
        row.setMsgId(cmd.msgId());
        row.setMsgType(cmd.msgType());
        row.setDimType(cmd.dimTypeCode());
        row.setBeforeJson(writeJson(before));
        row.setAfterJson(writeJson(after));
        row.setResult(result);
        row.setOpUser(cmd.opUser() == null ? "" : cmd.opUser());
        // create_time 同时是分区键，必须显式带值：默认值可用但分区维护与清理依赖它可预期。
        row.setCreateTime(now);
        recordMapper.insert(row);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("change record snapshot not serializable", e);
        }
    }
}
