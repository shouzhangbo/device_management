package com.example.device.core.mapper;

import com.example.device.core.entity.MqConsumeRecordDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消费幂等记录的持久层。
 * <p>
 * {@code uk_source_msg} 是幂等正确性的<b>唯一依据</b>：Redis 去重键只是挡并发的廉价前置，
 * 二者角色不可互换（{@code tech-design} TD-002）。因此本接口不提供"先查再插"的方法，
 * 重复判定完全交给唯一索引 + {@code DuplicateKeyException}。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface MqConsumeRecordMapper {

    /**
     * 事务内的<b>第一条</b>写语句。
     *
     * @param record 幂等记录
     * @return 受影响行数
     */
    int insert(MqConsumeRecordDO record);

    /**
     * 回写消费状态（成功 / 失败待重试 / 已进死信）。
     *
     * @param source     来源编码
     * @param msgId      消息 ID
     * @param status     目标状态：1-成功 2-失败待重试 3-已进入死信
     * @param failReason 失败原因摘要，调用方负责截断到列宽
     * @return 受影响行数
     */
    int updateStatus(@Param("source") String source, @Param("msgId") String msgId,
                     @Param("status") int status, @Param("failReason") String failReason);

    /**
     * 统计保留期外的行数，删除前先打日志（EP-008 误删保护）。
     *
     * @param before 保留期界（不含）
     * @return 待删行数
     */
    long countConsumedBefore(@Param("before") LocalDateTime before);

    /**
     * EP-008 按保留期分批清理，必须带 {@code LIMIT} 避免大事务。
     *
     * @param before 保留期界（不含）
     * @param limit  单批行数
     * @return 删除行数
     */
    int deleteConsumedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
