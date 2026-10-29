package com.example.device.core.mapper;

import com.example.device.core.entity.DeviceInfoChangeRecordDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设备变更流水的持久层：只增不改。
 * <p>
 * 表按 {@code create_time} 月分区，因此插入必须让 {@code create_time} 有确定值；
 * 清理走分区级 {@code DROP PARTITION}，本类的 {@link #deleteBefore} 只为非分区环境留一个兜底入口，
 * EP-008 主路径不使用它。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface DeviceInfoChangeRecordMapper {

    /**
     * 写入一条流水。
     *
     * @param record 流水行，{@code result} 只允许 1/3/4
     * @return 受影响行数
     */
    int insert(DeviceInfoChangeRecordDO record);

    /**
     * EP-010 时间轴分页：走 {@code idx_sn_create_time}，按时间倒序。
     * <p>
     * 注意 {@code WHERE sn = ?} <b>不含分区键</b>，因此没有分区裁剪（{@code tech-design} §9.1 纠正），
     * 靠强制时间范围与深翻页上限约束代价。
     * </p>
     *
     * @param sn    设备序列号
     * @param limit 每页条数
     * @param offset 偏移
     * @return 当前页流水
     */
    List<DeviceInfoChangeRecordDO> selectBySnPaged(@Param("sn") String sn, @Param("limit") int limit,
                                                   @Param("offset") int offset);

    /**
     * 时间轴总数。
     *
     * @param sn 设备序列号
     * @return 该设备的流水条数
     */
    long countBySn(@Param("sn") String sn);

    /**
     * 按时间范围过滤的时间轴查询（EP-010 的可选时间条件，用于收窄扫描分区）。
     *
     * @param sn    设备序列号
     * @param from  起始时间（含）
     * @param to    截止时间（含）
     * @param limit 每页条数
     * @param offset 偏移
     * @return 当前页流水
     */
    List<DeviceInfoChangeRecordDO> selectBySnInRange(@Param("sn") String sn, @Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to, @Param("limit") int limit,
                                                     @Param("offset") int offset);

    /**
     * 兜底分批删除（仅用于未启用分区的测试环境）。
     *
     * @param before 时间界（不含）
     * @param limit  单批行数，禁止无 LIMIT 的全表删除
     * @return 删除行数
     */
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
