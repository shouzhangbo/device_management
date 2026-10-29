package com.example.device.core.service;

import com.example.device.core.applier.ApplyCommand;
import com.example.device.core.applier.ColumnBinding;
import com.example.device.core.applier.ApplyOutcome;
import com.example.device.core.applier.DimColumns;
import com.example.device.core.applier.DeviceInfoApplier;
import com.example.device.core.applier.OptimisticConflictException;
import com.example.device.core.auth.DimAuthGuard;
import com.example.device.core.dim.DimSpec;
import com.example.device.core.dim.DimType;
import com.example.device.core.dto.ChangeRecordItem;
import com.example.device.core.dto.DeviceAdminItem;
import com.example.device.core.dto.PageData;
import com.example.device.core.entity.DeviceInfoChangeRecordDO;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.mapper.DeviceInfoChangeRecordMapper;
import com.example.device.core.mapper.DeviceInfoMapper;
import com.example.device.core.query.DeviceQueryFilter;
import com.example.device.core.query.PublicAssembler;
import com.example.device.core.web.BizException;
import com.example.device.core.web.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 后台写侧编排（EP-004 / EP-005 / EP-009）与后台读侧（EP-003 / EP-010）。
 * <p>
 * <b>本类不开事务</b>：设备主档的事务只存在于 {@link DeviceInfoApplier}，
 * 任何进程自己拼"更新 + 流水"两步写都会绕过幂等（{@code tech-design} §11.2 缺陷 1）。
 * 本类做的是三件事：授权检查 → 组 {@link ApplyCommand} → 把终态翻译成 ErrorCode。
 * </p>
 * <p>
 * 冲突策略与消费侧<b>相反</b>：后台 CAS 失败直接 {@code 409/4091} 且不重试
 * （R5——用过期编辑内容自动重投会覆盖外部最新值）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Service
public class DeviceAdminService {

    /** 后台侧固定的来源编码：授权表里 {@code source=ADMIN} 才有对应维度的写权限。 */
    public static final String ADMIN_SOURCE = "ADMIN";

    private static final Logger LOG = LoggerFactory.getLogger(DeviceAdminService.class);

    private final DeviceInfoApplier applier;
    private final DeviceInfoMapper deviceInfoMapper;
    private final DeviceInfoChangeRecordMapper changeRecordMapper;
    private final DimAuthGuard authGuard;
    private final PublicAssembler assembler;
    private final ObjectMapper objectMapper;

    /**
     * @param applier             唯一事务入口
     * @param deviceInfoMapper    主档读方法
     * @param changeRecordMapper  流水读方法
     * @param authGuard           维度授权守卫
     * @param assembler           可见列装配
     * @param objectMapper        流水 JSON 快照的解析
     */
    public DeviceAdminService(DeviceInfoApplier applier, DeviceInfoMapper deviceInfoMapper,
                             DeviceInfoChangeRecordMapper changeRecordMapper, DimAuthGuard authGuard,
                             PublicAssembler assembler, ObjectMapper objectMapper) {
        this.applier = applier;
        this.deviceInfoMapper = deviceInfoMapper;
        this.changeRecordMapper = changeRecordMapper;
        this.authGuard = authGuard;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
    }

    /**
     * 建档（EP-004）。
     * <p>
     * 状态列与时间列不接受入参，全部取 DDL 默认值；{@code maintain_*} 由服务端写；
     * 成功后必须删缓存——该 SN 可能已被查询过并留下 {@code __EMPTY__} 哨兵（§11.2 缺陷 9）。
     * </p>
     *
     * @param cmd      建档命令
     * @param operator 操作人拼音账号
     * @return 新建的主档行
     * @throws BizException {@code 4031} 未授权 / {@code 4090} SN 已存在
     */
    public DeviceAdminItem create(AdminCommands.Create cmd, String operator) {
        authGuard.checkOrThrow(DimType.PROFILE, ADMIN_SOURCE);
        ApplyCommand apply = new ApplyCommand(cmd.sn(), DimType.PROFILE,
                DimSpec.ADMIN_CREATE, ADMIN_SOURCE, adminMsgId(),
                profileColumns(cmd.modelCode(), cmd.modelName(), cmd.remark(), cmd.imageUrl(), operator),
                null, true, null, operator, "");
        try {
            applier.applyCreate(apply);
        } catch (DuplicateKeyException e) {
            LOG.info("admin create hits uk_sn, sn={}", cmd.sn());
            throw new BizException(ErrorCode.SN_ALREADY_EXISTS);
        }
        return adminItemOf(cmd.sn());
    }

    /**
     * 档案编辑（EP-005）：PROFILE 全量替换 + MAINTAIN 自动写入，<b>一次 CAS、一条流水</b>。
     * <p>
     * 拆成两次更新会让第二次必然版本冲突（{@code data-model} §8.4 硬规则 3）。
     * </p>
     *
     * @param sn       设备序列号
     * @param cmd      编辑命令（含 {@code version}）
     * @param operator 操作人拼音账号
     * @return 编辑后的主档行（含新 {@code version}，供连续编辑）
     * @throws BizException {@code 4040} 不存在或已删除 / {@code 4091} 版本冲突
     */
    public DeviceAdminItem updateProfile(String sn, AdminCommands.ProfileUpdate cmd, String operator) {
        authGuard.checkOrThrow(DimType.PROFILE, ADMIN_SOURCE);
        authGuard.checkOrThrow(DimType.MAINTAIN, ADMIN_SOURCE);
        requireVisible(sn);
        ApplyCommand apply = new ApplyCommand(sn, DimType.PROFILE,
                DimSpec.ADMIN_PROFILE_UPDATE, ADMIN_SOURCE, adminMsgId(),
                profileColumns(cmd.modelCode(), cmd.modelName(), cmd.remark(), cmd.imageUrl(), operator),
                null, false, cmd.version(), operator, "");
        applyOrConflict(apply);
        return adminItemOf(sn);
    }

    /**
     * 逻辑删除（EP-009）。成功即 {@code 204}，无响应体。
     *
     * @param sn       设备序列号
     * @param version  乐观锁版本
     * @param operator 操作人拼音账号
     * @throws BizException {@code 4040} 不存在或已删除（重复删除走这一支）/ {@code 4091} 版本冲突
     */
    public void delete(String sn, int version, String operator) {
        requireVisible(sn);
        ApplyCommand apply = new ApplyCommand(sn, null, DimSpec.ADMIN_DELETE,
                ADMIN_SOURCE, adminMsgId(), List.of(), null, false, version, operator, "");
        try {
            applier.applyDelete(apply, version);
        } catch (OptimisticConflictException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT);
        }
    }

    /**
     * 列表（EP-003）。筛选条件与深翻页上限由 {@link DeviceQueryFilter#validate()} 在进入 SQL 前把关。
     *
     * @param filter 已带默认值的筛选条件
     * @return 分页数据（后台可见 23 列）
     */
    public PageData<DeviceAdminItem> list(DeviceQueryFilter filter) {
        filter.validate();
        long total = deviceInfoMapper.countAdminPage(filter.sn(), filter.escapedSnPrefix(),
                filter.modelCode(), filter.onlineStatus(), filter.produceTimeStart(),
                filter.produceTimeEnd(), filter.includeDeleted());
        List<DeviceInfoDO> rows = total == 0 ? List.of()
                : deviceInfoMapper.selectAdminPage(filter.sn(), filter.escapedSnPrefix(),
                        filter.modelCode(), filter.onlineStatus(), filter.produceTimeStart(),
                        filter.produceTimeEnd(), filter.includeDeleted(), filter.pageSize(), filter.offset());
        return new PageData<>(rows.stream().map(assembler::toAdminItem).toList(),
                total, filter.page(), filter.pageSize());
    }

    /**
     * 变更流水（EP-010）：设备不存在 → {@code 4040}；存在但无记录 → 空列表而不是 404。
     *
     * @param sn       设备序列号
     * @param page     页码，从 1 起
     * @param pageSize 每页条数
     * @param maxOffset 深翻页上限
     * @return 分页流水，按 {@code create_time} 倒序
     * @throws BizException {@code 4040} 设备不存在或已逻辑删除 / {@code 4002} 深翻页超上限
     */
    public PageData<ChangeRecordItem> changeRecords(String sn, int page, int pageSize, int maxOffset) {
        requireVisible(sn);
        long total = changeRecordMapper.countBySn(sn);
        int offset = (page - 1) * pageSize;
        if (offset > maxOffset) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR,
                    "offset exceeds device.admin.max-offset=" + maxOffset);
        }
        List<DeviceInfoChangeRecordDO> rows = total == 0 ? List.of()
                : changeRecordMapper.selectBySnPaged(sn, pageSize, offset);
        return new PageData<>(rows.stream().map(this::toItem).toList(), total, page, pageSize);
    }

    private void applyOrConflict(ApplyCommand apply) {
        ApplyOutcome outcome;
        try {
            outcome = applier.apply(apply);
        } catch (OptimisticConflictException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.SN_ALREADY_EXISTS);
        }
        if (outcome == ApplyOutcome.MISSING_DEVICE_RECORDED) {
            // 读-写之间行被并发删除：与"进入前就不存在"同样处理。
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private DeviceAdminItem adminItemOf(String sn) {
        DeviceInfoDO row = deviceInfoMapper.selectBySnVisible(sn);
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return assembler.toAdminItem(row);
    }

    private void requireVisible(String sn) {
        if (deviceInfoMapper.selectBySnVisible(sn) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /**
     * PROFILE 全量列 + MAINTAIN 两列合成<b>一次</b>写入的列绑定。
     * <p>
     * {@code PUT} 语义下未传的档案列要写成空串（而不是"保持原值"），因此四列恒定出现；
     * {@code maintain_*} 由服务端写、不接受请求参数（{@code data-model} §7.1）。
     * </p>
     */
    private List<ColumnBinding> profileColumns(
            String modelCode, String modelName, String remark, String imageUrl, String operator) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("model_code", modelCode == null ? "" : modelCode);
        values.put("model_name", modelName == null ? "" : modelName);
        values.put("remark", remark == null ? "" : remark);
        values.put("image_url", imageUrl == null ? "" : imageUrl);
        values.put("maintain_user", operator);
        values.put("maintain_time", LocalDateTime.now());
        try {
            return DimColumns.fromValues(
                    List.of(DimType.PROFILE,
                            DimType.MAINTAIN), values);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, e.getMessage());
        }
    }

    private static String adminMsgId() {
        return "ADM-" + UUID.randomUUID();
    }

    private ChangeRecordItem toItem(DeviceInfoChangeRecordDO row) {
        return new ChangeRecordItem(row.getCreateTime(), row.getSource(), row.getMsgId(), row.getMsgType(),
                row.getDimType(), readJson(row.getBeforeJson()), readJson(row.getAfterJson()),
                row.getResult(), row.getOpUser());
    }

    private JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            LOG.warn("change record json snapshot unparsable, length={}", text.length());
            return null;
        }
    }
}
