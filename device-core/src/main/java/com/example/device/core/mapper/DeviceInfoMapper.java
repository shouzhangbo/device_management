package com.example.device.core.mapper;

import com.example.device.core.applier.ColumnBinding;
import com.example.device.core.entity.DeviceInfoDO;
import com.example.device.core.query.DeviceQueryFilter;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设备主档的持久层入口。
 * <p>
 * 写方法只有三个：{@link #updateDimCas}、{@link #insert} 与 {@link #markDeletedCas}
 * （后者是维度白名单之外唯一的例外，因为逻辑删除不属于任何维度）。
 * 两条读路径方法<b>必须分开</b>：消费侧要看到已逻辑删除的行（R6），对外与后台列表看不到。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface DeviceInfoMapper {

    /**
     * 维度列的条件更新（CAS）。
     * <p>
     * {@code WHERE sn = #{sn} AND version = #{version}}，<b>不加</b> {@code deleted} 过滤：
     * 已逻辑删除的设备仍接受外部维度更新（R6），只是查询侧不可见。
     * 影响行数 0 一律解释为版本冲突。
     * </p>
     *
     * @param sn       设备序列号
     * @param version  期望的当前版本
     * @param columns  本次要写的列，只允许来自 {@code DimSpec} 白名单
     * @return 受影响行数（0 或 1）
     */
    int updateDimCas(@Param("sn") String sn, @Param("version") int version,
                     @Param("columns") List<ColumnBinding> columns);

    /**
     * 新建一行设备主档。
     * <p>
     * 出厂建档（消息侧）与后台建档共用本方法；{@code uk_sn} 冲突由调用方判定：
     * 消息侧进事务首条写语句、后台侧转 {@code 409/4090}。
     * </p>
     *
     * @param record 待插入行，未赋值的维度列依赖 DDL 默认值
     * @return 受影响行数
     */
    int insert(DeviceInfoDO record);

    /**
     * 逻辑删除（CAS）。
     *
     * @param sn      设备序列号
     * @param version 期望的当前版本
     * @return 受影响行数（0 表示版本冲突或行不存在）
     */
    int markDeletedCas(@Param("sn") String sn, @Param("version") int version);

    /**
     * 按 SN 读一行，<b>包含</b>已逻辑删除的行。
     *
     * @param sn 设备序列号
     * @return 主档行，不存在时为 {@code null}
     */
    DeviceInfoDO selectBySn(@Param("sn") String sn);

    /**
     * 按 SN 读一行，只返回未逻辑删除的行；缓存回源用。
     *
     * @param sn 设备序列号
     * @return 可见主档行，不存在或已删除时为 {@code null}
     */
    DeviceInfoDO selectBySnVisible(@Param("sn") String sn);

    /**
     * 批量按 SN 读，只返回未逻辑删除的行；一次 {@code IN}，禁止循环单查。
     *
     * @param sns 设备序列号列表
     * @return 命中的行，顺序不保证
     */
    List<DeviceInfoDO> selectBySnList(@Param("sns") List<String> sns);

    /**
     * 按更新时间增量扫描，EP-007 缓存对账用；走 {@code idx_update_time}。
     *
     * @param after 上轮水位（不含）
     * @param limit 单批上限
     * @return 该批次内的行
     */
    List<DeviceInfoDO> selectUpdatedAfter(@Param("after") LocalDateTime after, @Param("limit") int limit);

    /**
     * 后台列表分页；必须带至少一个高区分度条件，由 {@link DeviceQueryFilter#validate()} 在进入本方法前校验（400/4004）。
     * <p>
     * 参数逐个展开而不是传 filter 对象：MyBatis 的 OGNL 取值依赖 {@code getXxx()} 约定，
     * 传 record 会让 SQL 层依赖框架对 record 访问器的支持程度，不值得冒这个险。
     * </p>
     *
     * @param sn               精确 SN
     * @param snPrefix         SN 前缀
     * @param modelCode        型号编码
     * @param onlineStatus     在线状态
     * @param produceTimeStart 生产时间起（含）
     * @param produceTimeEnd   生产时间止（含）
     * @param includeDeleted   是否包含逻辑删除行
     * @param limit            每页条数
     * @param offset           偏移
     * @return 当前页数据
     */
    List<DeviceInfoDO> selectAdminPage(@Param("sn") String sn, @Param("snPrefix") String snPrefix,
                                       @Param("modelCode") String modelCode,
                                       @Param("onlineStatus") Integer onlineStatus,
                                       @Param("produceTimeStart") LocalDateTime produceTimeStart,
                                       @Param("produceTimeEnd") LocalDateTime produceTimeEnd,
                                       @Param("includeDeleted") boolean includeDeleted,
                                       @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 后台列表总数。
     *
     * @param sn               精确 SN
     * @param snPrefix         SN 前缀
     * @param modelCode        型号编码
     * @param onlineStatus     在线状态
     * @param produceTimeStart 生产时间起（含）
     * @param produceTimeEnd   生产时间止（含）
     * @param includeDeleted   是否包含逻辑删除行
     * @return 满足条件的行数
     */
    long countAdminPage(@Param("sn") String sn, @Param("snPrefix") String snPrefix,
                        @Param("modelCode") String modelCode, @Param("onlineStatus") Integer onlineStatus,
                        @Param("produceTimeStart") LocalDateTime produceTimeStart,
                        @Param("produceTimeEnd") LocalDateTime produceTimeEnd,
                        @Param("includeDeleted") boolean includeDeleted);
}
