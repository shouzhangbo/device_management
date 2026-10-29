package com.example.device.core.mapper;

import com.example.device.core.entity.MqDeadLetterDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 死信归档的持久层（EP-011 的可查询、可重放入口）。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface MqDeadLetterMapper {

    /**
     * 归档一条死信，保存原始消息体与原始 offset/topic。
     *
     * @param record 死信行
     * @return 受影响行数
     */
    int insert(MqDeadLetterDO record);

    /**
     * 按状态与来源分页；列表查询<b>不取</b> {@code content}，它只在详情返回（响应体积红线）。
     *
     * @param status 处理状态：0-待处理 1-已重放 2-已丢弃；传 {@code null} 表示不过滤
     * @param source 来源编码；传 {@code null} 表示不过滤
     * @param limit  每页条数
     * @param offset 偏移
     * @return 当前页死信（content 为 null）
     */
    List<MqDeadLetterDO> selectPaged(@Param("status") Integer status, @Param("source") String source,
                                     @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 按状态与来源计数。
     *
     * @param status 处理状态，{@code null} 表示全部
     * @param source 来源编码，{@code null} 表示全部
     * @return 总行数
     */
    long countByStatus(@Param("status") Integer status, @Param("source") String source);

    /**
     * 详情：含 {@code content} 原文。
     *
     * @param id 主键
     * @return 死信行，不存在时为 {@code null}
     */
    MqDeadLetterDO selectById(@Param("id") long id);

    /**
     * 状态流转的条件更新：{@code WHERE id = #{id} AND status = #{from}}。
     * <p>
     * 不加 {@code status} 条件时，两个运维同时点重放会二次处理同一条消息（{@code tech-design} §5.2 EP-011）。
     * </p>
     *
     * @param id         主键
     * @param from       期望的当前状态
     * @param to         目标状态
     * @param handleUser 处理人（JWT {@code sub}）
     * @param handleTime 处理时间
     * @return 受影响行数，0 表示已被他人处理
     */
    int casUpdateStatus(@Param("id") long id, @Param("from") int from, @Param("to") int to,
                        @Param("handleUser") String handleUser, @Param("handleTime") LocalDateTime handleTime);

    /**
     * 待处理死信计数，供告警扫描（MQ 规范 §4.4【强制】状态列在左的索引）。
     *
     * @return 待处理条数
     */
    long countPending();
}
