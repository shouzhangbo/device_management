package com.example.device.ingest.cron;

import com.example.device.core.config.CronProperties;
import com.example.device.core.mapper.DeviceInfoPartitionMapper;
import com.example.device.core.mapper.MqConsumeRecordMapper;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EP-008 归档清理：幂等记录按行分批删、流水按分区整月 {@code DROP PARTITION}。
 * <p>
 * 两类删除的共同红线：<b>保留期或批量为 0/负数时任务拒绝执行</b>，本类没有"清空全部"入口，
 * 也不用 {@code TRUNCATE}（{@code java_logging_specification} §批量操作【强制】）。
 * </p>
 * <p>
 * {@code mq_consume_record} 只做行删除、不做分区：它是唯一索引 {@code uk_source_msg} 的载体，
 * 分区表要求唯一键包含分区键，一旦分区就会破坏幂等语义（{@code data-model} §8.3）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class ArchiveCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ArchiveCleanupJob.class);

    /** 任务名，同时是 cron 锁的第二段。 */
    public static final String JOB = "archive-cleanup";

    /** 周期推算失败时的兜底（每日一次），不是新增配置键。 */
    private static final Duration FALLBACK_PERIOD = Duration.ofDays(1);

    private final MqConsumeRecordMapper consumeRecordMapper;
    private final DeviceInfoPartitionMapper partitionMapper;
    private final CronLock cronLock;
    private final CronProperties cronProperties;
    private final String cron;
    private final int recordRetentionDays;
    private final int recordRetentionMonths;
    private final int deleteBatchSize;

    /**
     * @param consumeRecordMapper   幂等记录持久层
     * @param partitionMapper       分区 DDL 入口
     * @param cronLock              跨实例互斥
     * @param cronProperties        锁比例参数
     * @param cron                  清理调度表达式
     * @param recordRetentionDays   幂等记录保留天数
     * @param recordRetentionMonths 流水分区保留月数
     * @param deleteBatchSize       单批删除行数
     */
    public ArchiveCleanupJob(MqConsumeRecordMapper consumeRecordMapper, DeviceInfoPartitionMapper partitionMapper,
                             CronLock cronLock, CronProperties cronProperties,
                             @Value("${device.archive.cleanup-cron:0 30 3 * * ?}") String cron,
                             @Value("${device.consume.record-retention-days:90}") int recordRetentionDays,
                             @Value("${device.record.retention-months:12}") int recordRetentionMonths,
                             @Value("${archive.delete.batch-size:1000}") int deleteBatchSize) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.partitionMapper = partitionMapper;
        this.cronLock = cronLock;
        this.cronProperties = cronProperties;
        this.cron = cron;
        this.recordRetentionDays = recordRetentionDays;
        this.recordRetentionMonths = recordRetentionMonths;
        this.deleteBatchSize = deleteBatchSize;
    }

    /**
     * 非法保留期/批量直接让进程启动失败，而不是等到夜里删错数据。
     */
    @PostConstruct
    void validateRetention() {
        if (recordRetentionDays <= 0 || recordRetentionMonths <= 0 || deleteBatchSize <= 0) {
            throw new IllegalStateException("archive cleanup refused to start: retention-days="
                    + recordRetentionDays + ", retention-months=" + recordRetentionMonths
                    + ", batch-size=" + deleteBatchSize + " must all be positive");
        }
    }

    /**
     * 一轮清理：先删幂等记录，再删过期分区（同一把锁、同一个低峰窗口）。
     */
    @Scheduled(cron = "${device.archive.cleanup-cron:0 30 3 * * ?}")
    public void cleanConsumeRecords() {
        Duration period = CronSchedule.period(cron, FALLBACK_PERIOD);
        Duration lockTtl = CronSchedule.lockTtl(period, cronProperties.lockTtlRatioOrDefault());
        if (!cronLock.tryLock(JOB, lockTtl)) {
            log.debug("archive cleanup skipped, another instance holds the lock");
            return;
        }
        try {
            deleteConsumeRecords(lockTtl);
            dropExpiredPartitions();
        } catch (RuntimeException e) {
            log.error("archive cleanup round failed", e);
        } finally {
            cronLock.unlock(JOB);
        }
    }

    /**
     * 删除保留期外的幂等记录，分批提交，每批之间检查锁余量。
     *
     * @param lockTtl 锁存活时长，用于判定是否需要截断本轮
     * @return 删除总行数
     */
    public int deleteConsumeRecords(Duration lockTtl) {
        LocalDateTime before = LocalDateTime.now().minusDays(recordRetentionDays);
        long total = consumeRecordMapper.countConsumedBefore(before);
        log.info("archive cleanup start, consume_time before={}, planned rows={}, batch={}",
                before, total, deleteBatchSize);
        int deleted = 0;
        while (true) {
            int rows = consumeRecordMapper.deleteConsumedBefore(before, deleteBatchSize);
            deleted += rows;
            log.info("archive cleanup batch done, batchDeleted={}, totalDeleted={}", rows, deleted);
            if (rows < deleteBatchSize) {
                break;
            }
            if (truncated(lockTtl)) {
                log.warn("archive cleanup truncated, lock remaining below safety margin, deleted={}", deleted);
                break;
            }
        }
        log.info("archive cleanup done, planned={}, deleted={}", total, deleted);
        return deleted;
    }

    /**
     * 删除超出保留期的流水分区。
     * <p>
     * {@code pmax} 非空说明有月份漏建了分区，数据已落进兜底分区——此时<b>禁止</b>执行任何
     * {@code DROP PARTITION}，否则会连带删掉尚未归类到月份的数据（{@code data-model} §6.2 注记）。
     * </p>
     *
     * @return 删除的分区数
     */
    public int dropExpiredPartitions() {
        long pmaxRows = partitionMapper.countRowsIn(CronSql.PMAX);
        if (pmaxRows > 0) {
            log.error("pmax partition is not empty, partition prebuild missed, skip dropping this round, rows={}",
                    pmaxRows);
            return 0;
        }
        YearMonth threshold = YearMonth.now().minusMonths(recordRetentionMonths);
        List<String> partitions = partitionMapper.listPartitions();
        int dropped = 0;
        for (String partition : partitions) {
            if (CronSql.PMAX.equals(partition)) {
                continue;
            }
            YearMonth month = CronSql.monthOf(partition);
            if (month == null || !month.isBefore(threshold)) {
                continue;
            }
            partitionMapper.dropPartition(CronSql.requirePartition(partition));
            log.info("archive cleanup dropped partition, partition={}, retentionMonths={}",
                    partition, recordRetentionMonths);
            dropped++;
        }
        log.info("archive cleanup partition pass done, dropped={}, threshold={}", dropped, threshold);
        return dropped;
    }

    private boolean truncated(Duration lockTtl) {
        long margin = (long) (lockTtl.toMillis() * cronProperties.safetyMarginRatioOrDefault());
        return cronLock.remainingMillis(JOB) < margin;
    }
}
