package com.example.device.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 流水分区的运维入口（EP-008）。
 * <p>
 * 本类是<b>唯一</b>允许出现动态 SQL 标识符的地方：表名是代码常量，分区名与分区边界必须由
 * {@code CronSql} 按 {@code ^p\d{6}$} / {@code ^\d{4}-\d{2}-\d{2}$} 生成后再传入，
 * 任务不接受任何外部参数（R14）。
 * </p>
 * <p>
 * 新增分区用 {@code REORGANIZE PARTITION pmax} 而不是 {@code ADD PARTITION}：{@code pmax} 存在时
 * {@code ADD PARTITION} 会因"边界必须严格递增"失败，而 {@code REORGANIZE} 在"只向前追加 +
 * 保留 {@code pmax}"这一点上与设计要求等价（{@code data-model} §6.2 注记）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface DeviceInfoPartitionMapper {

    /**
     * 列出 {@code device_info_change_record} 的全部已建分区。
     *
     * @return 分区名，按分区序号升序；表未分区时为空列表
     */
    List<String> listPartitions();

    /**
     * 统计某个分区内的行数，用于 {@code pmax} 漏建告警。
     *
     * @param partition 分区名，必须已被 {@code CronSql} 校验
     * @return 行数
     */
    long countRowsIn(@Param("partition") String partition);

    /**
     * 把 {@code pmax} 拆成"新分区 + 新 {@code pmax}"。
     *
     * @param partition 新分区名，形如 {@code p202611}
     * @param boundary  新分区的上界日期（不含），形如 {@code 2026-12-01}
     * @return 受影响行数（DDL 恒为 0）
     */
    int reorganizeAddPartition(@Param("partition") String partition, @Param("boundary") String boundary);

    /**
     * 删除一个过期分区。
     *
     * @param partition 分区名，禁止传 {@code pmax}
     * @return 受影响行数（DDL 恒为 0）
     */
    int dropPartition(@Param("partition") String partition);
}
