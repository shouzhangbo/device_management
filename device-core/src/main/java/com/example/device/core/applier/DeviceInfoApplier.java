package com.example.device.core.applier;

import com.example.device.core.dim.DimType;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.entity.MqConsumeRecordDO;
import com.example.device.core.mapper.DeviceInfoMapper;
import com.example.device.core.mapper.MqConsumeRecordMapper;
import com.example.device.core.metrics.DeviceIngestMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全系统<b>唯一</b>持有设备主档写事务的组件。
 * <p>
 * 三个进程（消费、后台、死信重放）都必须经 {@link #apply(ApplyCommand)}（逻辑删经
 * {@link #applyDelete}，后台建档经 {@link #applyCreate}）落库，任何一处自己写 {@code UPDATE}
 * 都会绕过幂等或流水（{@code tech-design} §11.2 缺陷 1）。
 * </p>
 * <p>
 * 事务内顺序固定：幂等记录 → 读当前行 → 建档或 CAS → 流水。幂等记录最先写，
 * 是为了让"重复消息"在碰主档之前就被唯一索引挡掉；本方法<b>不</b>捕获
 * {@link org.springframework.dao.DuplicateKeyException}——在事务内捕获会把事务标记成
 * rollback-only，由外层（消费处理器）在事务<b>外</b>翻译成语义终态（R2）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Service
public class DeviceInfoApplier {

    private static final Logger log = LoggerFactory.getLogger(DeviceInfoApplier.class);

    private final DeviceInfoMapper deviceInfoMapper;
    private final MqConsumeRecordMapper consumeRecordMapper;
    private final DeviceRecorder recorder;
    private final CacheInvalidationRegistry cacheInvalidation;
    private final DeviceIngestMetrics metrics;

    /**
     * @param deviceInfoMapper   主档持久层
     * @param consumeRecordMapper 幂等记录持久层
     * @param recorder           流水封装
     * @param cacheInvalidation  提交后缓存失效注册器
     * @param metrics            指标出口
     */
    public DeviceInfoApplier(DeviceInfoMapper deviceInfoMapper, MqConsumeRecordMapper consumeRecordMapper,
                             DeviceRecorder recorder, CacheInvalidationRegistry cacheInvalidation,
                             DeviceIngestMetrics metrics) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.consumeRecordMapper = consumeRecordMapper;
        this.recorder = recorder;
        this.cacheInvalidation = cacheInvalidation;
        this.metrics = metrics;
    }

    /**
     * 落一次维度变更。
     *
     * @param cmd 写入意图
     * @return 终态：{@link ApplyOutcome#APPLIED} 或 {@link ApplyOutcome#MISSING_DEVICE_RECORDED}
     * @throws OptimisticConflictException 版本冲突，整个事务回滚（幂等记录一并回滚，重投仍可处理）
     * @throws org.springframework.dao.DuplicateKeyException 重复消息，由事务外的调用方翻译为
     *         {@link ApplyOutcome#DUPLICATE}
     */
    @Transactional(rollbackFor = Exception.class, timeoutString = "${device.ingest.tx-timeout-sec:3}")
    public ApplyOutcome apply(ApplyCommand cmd) {
        long startNanos = System.nanoTime();
        log.debug("apply dimension change, source={}, msg_id={}, msg_type={}, sn={}, dim={}",
                cmd.source(), cmd.msgId(), cmd.msgType(), cmd.sn(), cmd.dimTypeCode());
        consumeRecordMapper.insert(toConsumeRecord(cmd));
        DeviceInfoDO current = deviceInfoMapper.selectBySn(cmd.sn());
        ApplyOutcome outcome = current == null ? create(cmd) : update(cmd, current);
        metrics.recordApplyDuration(Duration.ofNanos(System.nanoTime() - startNanos));
        if (outcome == ApplyOutcome.APPLIED) {
            metrics.applied();
        }
        return outcome;
    }

    /**
     * 后台建档：<b>严格插入</b>，SN 已存在（含已逻辑删除的行）必须由 {@code uk_sn} 拒绝。
     * <p>
     * 不能复用 {@link #apply(ApplyCommand)}：那条路径在行已存在时走维度 CAS 更新，
     * 会把"重复建档"变成"用空值覆盖 PROFILE 四列并成功返回"，调用方却以为新建成功。
     * 严格插入失败时整事务回滚（幂等记录一并撤销），由 {@code DeviceAdminService} 在事务外
     * 翻译成 {@code 409/4090}。
     * </p>
     *
     * @param cmd 建档意图，{@code createIfAbsent} 必须为 {@code true}
     * @return 恒为 {@link ApplyOutcome#APPLIED}
     * @throws org.springframework.dao.DuplicateKeyException SN 已存在
     */
    @Transactional(rollbackFor = Exception.class, timeoutString = "${device.ingest.tx-timeout-sec:3}")
    public ApplyOutcome applyCreate(ApplyCommand cmd) {
        if (!cmd.createIfAbsent()) {
            throw new IllegalArgumentException("applyCreate requires createIfAbsent=true");
        }
        long startNanos = System.nanoTime();
        consumeRecordMapper.insert(toConsumeRecord(cmd));
        ApplyOutcome outcome = create(cmd);
        metrics.recordApplyDuration(Duration.ofNanos(System.nanoTime() - startNanos));
        metrics.applied();
        return outcome;
    }

    /**
     * 逻辑删除：维度白名单之外<b>唯一</b>允许的写操作，仍走同一个事务入口。
     * <p>
     * 删除不属于任何维度（{@code data-model} §8.4 后台三行的最后一行），所以不能复用
     * {@link #apply}；但幂等记录、流水、提交后删缓存三件事一件都不能少，因此在本类里单开一个
     * 同样带 {@code @Transactional} 的方法，而不是让 Service 自己拼两步写。
     * </p>
     *
     * @param cmd             删除意图（{@code dim} 为 {@code null}，流水 {@code dim_type} 写空串）
     * @param expectedVersion 调用方读到的当前版本
     * @throws OptimisticConflictException 版本不符或行已被逻辑删除，整事务回滚
     */
    @Transactional(rollbackFor = Exception.class, timeoutString = "${device.ingest.tx-timeout-sec:3}")
    public void applyDelete(ApplyCommand cmd, int expectedVersion) {
        consumeRecordMapper.insert(toConsumeRecord(cmd));
        int rows = deviceInfoMapper.markDeletedCas(cmd.sn(), expectedVersion);
        if (rows == 0) {
            metrics.casConflict();
            log.warn("delete cas conflict, sn={}, expected_version={}", cmd.sn(), expectedVersion);
            throw new OptimisticConflictException(cmd.sn(), expectedVersion);
        }
        // 删除不属任何维度，没有"该维度的列"可快照；只记录真正被改动的 deleted 位，形状与其它流水一致。
        recorder.recordApplied(cmd, Map.of("deleted", 0), Map.of("deleted", 1));
        cacheInvalidation.registerSnDelete(cmd.sn());
        log.info("device logically deleted, sn={}, version={}->{}", cmd.sn(), expectedVersion, expectedVersion + 1);
    }

    private ApplyOutcome create(ApplyCommand cmd) {
        if (!cmd.createIfAbsent()) {
            // 主档不存在且不建档：留痕 result=3、事务正常提交、消息照常 ack（R5）。
            recorder.recordMissing(cmd);
            log.info("device not found, record only, sn={}, msg_type={}", cmd.sn(), cmd.msgType());
            return ApplyOutcome.MISSING_DEVICE_RECORDED;
        }
        List<ColumnBinding> columns = withExt(cmd, bindingsOf(cmd));
        if (cmd.dim() == DimType.FACTORY && noneMatches(columns, DimColumns.REGISTER_STATUS)) {
            // 建档与置位同事务：不存在"有 SN 但未注册"的空窗。
            columns = with(columns, DimColumns.registerStatusOn());
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("create command carries no column");
        }
        DeviceInfoDO row = new DeviceInfoDO();
        row.setSn(cmd.sn());
        DimColumns.populate(row, columns);
        deviceInfoMapper.insert(row);
        recorder.recordCreated(cmd, columns);
        cacheInvalidation.registerSnDelete(cmd.sn());
        log.info("device created, sn={}, columns={}", cmd.sn(), columns.size());
        return ApplyOutcome.APPLIED;
    }

    private ApplyOutcome update(ApplyCommand cmd, DeviceInfoDO current) {
        List<ColumnBinding> columns = withExt(cmd, bindingsOf(cmd));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("update command carries no column: " + cmd.msgType());
        }
        int expected = cmd.expectedVersion() == null ? current.getVersion() : cmd.expectedVersion();
        int rows = deviceInfoMapper.updateDimCas(cmd.sn(), expected, columns);
        if (rows == 0) {
            // 只尝试一次：在 Applier 内部重读 version 再 CAS 用的仍是同一快照，必然再失败（R4）。
            metrics.casConflict();
            log.warn("optimistic lock conflict, sn={}, expected_version={}", cmd.sn(), expected);
            throw new OptimisticConflictException(cmd.sn(), expected);
        }
        Map<String, Object> before = DimColumns.snapshot(cmd.dimsInvolved(), current);
        Map<String, Object> after = new LinkedHashMap<>(before);
        columns.forEach(c -> after.put(c.column(), c.value()));
        recorder.recordApplied(cmd, before, after);
        cacheInvalidation.registerSnDelete(cmd.sn());
        log.info("dimension updated, sn={}, dim={}, version={}->{}", cmd.sn(), cmd.dimTypeCode(),
                expected, expected + 1);
        return ApplyOutcome.APPLIED;
    }

    /**
     * 幂等记录在事务开始时就写，此时尚未知道最终结局。
     * <p>
     * {@code status=1} 的语义是"随本事务一起提交的那次消费成功"——失败时整事务回滚，本行不存在；
     * 失败待重试与进死信由外层在事务<b>外</b>另写 {@code status=2/3} 的记录，两处口径不重叠。
     * </p>
     */
    private MqConsumeRecordDO toConsumeRecord(ApplyCommand cmd) {
        MqConsumeRecordDO record = new MqConsumeRecordDO();
        record.setMsgId(cmd.msgId());
        record.setSource(cmd.source());
        record.setBizType(cmd.bizType());
        record.setBizId(cmd.sn());
        record.setMsgType(cmd.msgType());
        record.setTopic(cmd.topic() == null ? "" : cmd.topic());
        record.setStatus(1);
        record.setFailReason("");
        record.setConsumeTime(LocalDateTime.now());
        return record;
    }

    private List<ColumnBinding> bindingsOf(ApplyCommand cmd) {
        return new ArrayList<>(cmd.columnsOrEmpty());
    }

    private List<ColumnBinding> withExt(ApplyCommand cmd, List<ColumnBinding> columns) {
        return cmd.extJson() == null ? columns : with(columns, new ColumnBinding(DimColumns.EXT_JSON, cmd.extJson()));
    }

    private static List<ColumnBinding> with(List<ColumnBinding> columns, ColumnBinding extra) {
        List<ColumnBinding> merged = new ArrayList<>(columns);
        merged.add(extra);
        return merged;
    }

    private static boolean noneMatches(List<ColumnBinding> columns, String column) {
        return columns.stream().noneMatch(c -> c.column().equals(column));
    }
}
