package com.example.device.core.service;

import com.example.device.core.applier.ApplyOutcome;
import com.example.device.core.applier.DeviceInfoApplier;
import com.example.device.core.applier.OptimisticConflictException;
import com.example.device.core.auth.DimAuthGuard;
import com.example.device.core.contract.EnvelopeParser;
import com.example.device.core.contract.EnvelopeValidator;
import com.example.device.core.contract.MessageEnvelope;
import com.example.device.core.contract.ParseResult;
import com.example.device.core.contract.ValidatedCommand;
import com.example.device.core.contract.ValidateResult;
import com.example.device.core.dto.DeadLetterDetail;
import com.example.device.core.dto.DeadLetterItem;
import com.example.device.core.dto.DeadLetterReplayResult;
import com.example.device.core.dto.PageData;
import com.example.device.core.entity.MqDeadLetterDO;
import com.example.device.core.mapper.MqDeadLetterMapper;
import com.example.device.core.support.TimeValues;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 死信运维入口（EP-011）：可查询、可重放、可丢弃（MQ 规范 §3.3【强制】）。
 * <p>
 * 重放<b>复用与消费完全相同的</b>解析 + 校验 + 授权 + {@link DeviceInfoApplier} 链路，
 * 不为"重放"开专用写方法，也不跳过幂等记录（{@code tech-design} §5.2 EP-011）。
 * </p>
 * <p>
 * <b>事务边界</b>：死信状态流转与业务落地必须在同一事务，且顺序是"先 CAS 状态、再 apply"。
 * 反过来（先 apply 再置状态）一旦中间失败，会出现"数据已落地但死信仍是待处理"，
 * 下次重放必然撞幂等唯一键；本方向失败则整事务回滚，死信回到 {@code PENDING}，可安全再试。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Service
public class DeadLetterService {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterService.class);

    private final MqDeadLetterMapper deadLetterMapper;
    private final EnvelopeParser envelopeParser;
    private final EnvelopeValidator envelopeValidator;
    private final DimAuthGuard authGuard;
    private final DeviceInfoApplier applier;

    /**
     * @param deadLetterMapper 死信持久层
     * @param envelopeParser   与消费侧同一个解析器
     * @param envelopeValidator 与消费侧同一个校验器
     * @param authGuard        与消费侧同一个授权守卫（重放不复用当初的结论）
     * @param applier          唯一事务入口
     */
    public DeadLetterService(MqDeadLetterMapper deadLetterMapper, EnvelopeParser envelopeParser,
                             EnvelopeValidator envelopeValidator, DimAuthGuard authGuard,
                             DeviceInfoApplier applier) {
        this.deadLetterMapper = deadLetterMapper;
        this.envelopeParser = envelopeParser;
        this.envelopeValidator = envelopeValidator;
        this.authGuard = authGuard;
        this.applier = applier;
    }

    /**
     * 列表：按状态与来源分页，<b>不返回</b> {@code content}。
     *
     * @param status    0/1/2，{@code null} 表示全部
     * @param source    来源编码，空或 {@code null} 表示不过滤
     * @param page      页码，从 1 起
     * @param pageSize  每页条数
     * @param maxOffset 深翻页上限
     * @return 分页死信
     * @throws BizException {@code 4002} 深翻页超上限
     */
    public PageData<DeadLetterItem> page(Integer status, String source, int page, int pageSize, int maxOffset) {
        int offset = (page - 1) * pageSize;
        if (offset > maxOffset) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR,
                    "offset exceeds device.admin.max-offset=" + maxOffset);
        }
        String sourceFilter = source == null || source.isBlank() ? null : source.trim();
        long total = deadLetterMapper.countByStatus(status, sourceFilter);
        List<MqDeadLetterDO> rows = total == 0 ? List.of()
                : deadLetterMapper.selectPaged(status, sourceFilter, pageSize, offset);
        return new PageData<>(rows.stream().map(DeadLetterService::toItem).toList(), total, page, pageSize);
    }

    /**
     * 详情：附 {@code content} 原文，不修复、不改写。
     *
     * @param id 死信主键
     * @return 详情
     * @throws BizException {@code 4040} 记录不存在
     */
    public DeadLetterDetail detail(long id) {
        MqDeadLetterDO row = deadLetterMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new DeadLetterDetail(row.getId(), row.getMsgId(), row.getSource(), row.getTopic(),
                row.getConsumerGroup(), row.getPartitionNo(), row.getOffsetNo(), row.getMsgKey(),
                row.getFailReason(), row.getRetryCount(), row.getStatus(), row.getHandleUser(),
                TimeValues.nullIfSentinel(row.getHandleTime()), row.getCreateTime(), row.getContent());
    }

    /**
     * 重放一条死信。
     *
     * @param id       死信主键
     * @param operator 处理人拼音账号（JWT {@code sub}）
     * @return 落地结果
     * @throws BizException {@code 4040} 不存在 / {@code 4093} 已处理 / {@code 4092} 幂等冲突（整事务回滚，
     *                      死信回到待处理）/ {@code 4002} 原文仍不合法 / {@code 4031} 授权仍未放开
     */
    @Transactional(rollbackFor = Exception.class, timeoutString = "${device.ingest.tx-timeout-sec:3}")
    public DeadLetterReplayResult replay(long id, String operator) {
        // 先判存在再 CAS：否则不存在的 id 也会得到 4093（"已处理"），而契约要求 404/4040。
        MqDeadLetterDO row = deadLetterMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        int claimed = deadLetterMapper.casUpdateStatus(id, DeadLetterStatus.PENDING.code(),
                DeadLetterStatus.REPLAYED.code(), operator, LocalDateTime.now());
        if (claimed == 0) {
            throw new BizException(ErrorCode.DEAD_LETTER_ALREADY_HANDLED);
        }
        ValidatedCommand cmd = parseAndValidate(row);
        authGuard.checkOrThrow(cmd.dim(), cmd.source());
        ApplyOutcome outcome;
        try {
            outcome = applier.apply(cmd.toApplyCommand(row.getTopic()));
        } catch (DuplicateKeyException e) {
            // 这里捕获 DuplicateKeyException 不违反 R2：本分支的目的就是让<b>整个</b>事务回滚，
            // 不存在"捕获后继续用同一事务写库"的路径；消费侧那条规则针对的是捕获后照常提交。
            LOG.info("replay hits idempotent record, deadLetterId={}, msg_id={}", id, row.getMsgId());
            throw new BizException(ErrorCode.REPLAY_HIT_IDEMPOTENT);
        } catch (OptimisticConflictException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT);
        }
        // 幂等冲突与校验失败都让事务回滚：状态 CAS 一并撤销，死信仍是待处理，运维修好配置可直接再点。
        return new DeadLetterReplayResult(id, DeadLetterStatus.REPLAYED.code(),
                outcome == ApplyOutcome.MISSING_DEVICE_RECORDED
                        ? DeadLetterReplayResult.DEVICE_NOT_FOUND : DeadLetterReplayResult.APPLIED,
                outcome == ApplyOutcome.MISSING_DEVICE_RECORDED ? 3 : 1);
    }

    /**
     * 丢弃：只有状态流转与审计字段，不碰主档与幂等表。
     *
     * @param id       死信主键
     * @param operator 处理人
     * @throws BizException {@code 4040} 不存在 / {@code 4093} 已处理或已丢弃
     */
    public void discard(long id, String operator) {
        if (deadLetterMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        int rows = deadLetterMapper.casUpdateStatus(id, DeadLetterStatus.PENDING.code(),
                DeadLetterStatus.DISCARDED.code(), operator, LocalDateTime.now());
        if (rows == 0) {
            throw new BizException(ErrorCode.DEAD_LETTER_ALREADY_HANDLED);
        }
        LOG.info("dead letter discarded, id={}, operator={}", id, operator);
    }

    private ValidatedCommand parseAndValidate(MqDeadLetterDO row) {
        ParseResult parsed = envelopeParser.parse(row.getContent() == null
                ? new byte[0] : row.getContent().getBytes(StandardCharsets.UTF_8));
        if (parsed.isBad()) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR,
                    "dead letter content is still unparsable: " + parsed.reason());
        }
        MessageEnvelope envelope = parsed.envelope();
        ValidateResult validated = envelopeValidator.validate(envelope);
        if (!validated.isAccepted()) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, validated.reason());
        }
        return validated.command();
    }

    private static DeadLetterItem toItem(MqDeadLetterDO row) {
        return new DeadLetterItem(row.getId(), row.getMsgId(), row.getSource(), row.getTopic(),
                row.getConsumerGroup(), row.getPartitionNo(), row.getOffsetNo(), row.getMsgKey(),
                row.getFailReason(), row.getRetryCount(), row.getStatus(), row.getHandleUser(),
                TimeValues.nullIfSentinel(row.getHandleTime()), row.getCreateTime());
    }
}
