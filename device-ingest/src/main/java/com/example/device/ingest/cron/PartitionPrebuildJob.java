package com.example.device.ingest.cron;

import com.example.device.core.config.CronProperties;
import com.example.device.core.mapper.DeviceInfoPartitionMapper;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EP-008 分区预建：保证"当前月 + 次月"分区存在，防止数据落进 {@code pmax} 后无法清理。
 * <p>
 * 漏建的后果是静默的——插入不会报错，行只是进了 {@code pmax}，等发现时 {@code DROP PARTITION}
 * 已经清不掉（{@code data-model} §6.2 注记）。因此本任务每次都校验 {@code pmax} 行数并 ERROR 告警。
 * </p>
 * <p>
 * 只允许向前追加，{@code pmax} 永远保留；分区名与边界全部由 {@link CronSql} 生成，任务没有外部入参（R14）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Component
public class PartitionPrebuildJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionPrebuildJob.class);

    /** 任务名，同时是 cron 锁的第二段。 */
    public static final String JOB = "partition-prebuild";

    /** 周期推算失败时的兜底（每月一次），不是新增配置键。 */
    private static final Duration FALLBACK_PERIOD = Duration.ofDays(30);

    private final DeviceInfoPartitionMapper partitionMapper;
    private final CronLock cronLock;
    private final CronProperties cronProperties;
    private final String cron;

    /**
     * @param partitionMapper 分区 DDL 入口
     * @param cronLock        跨实例互斥
     * @param cronProperties  锁比例参数
     * @param cron            预建调度表达式
     */
    public PartitionPrebuildJob(DeviceInfoPartitionMapper partitionMapper, CronLock cronLock,
                                CronProperties cronProperties,
                                @Value("${device.archive.prebuild-cron:0 0 4 25 * ?}") String cron) {
        this.partitionMapper = partitionMapper;
        this.cronLock = cronLock;
        this.cronProperties = cronProperties;
        this.cron = cron;
    }

    /**
     * 预建分区：每月 25 日建次月分区，并顺带补齐当前月。
     */
    @Scheduled(cron = "${device.archive.prebuild-cron:0 0 4 25 * ?}")
    public void prebuildNextMonth() {
        Duration period = CronSchedule.period(cron, FALLBACK_PERIOD);
        Duration lockTtl = CronSchedule.lockTtl(period, cronProperties.lockTtlRatioOrDefault());
        if (!cronLock.tryLock(JOB, lockTtl)) {
            log.debug("partition prebuild skipped, another instance holds the lock");
            return;
        }
        try {
            YearMonth current = YearMonth.now();
            List<String> existing = partitionMapper.listPartitions();
            ensure(current, existing);
            ensure(current.plusMonths(1), existing);
            warnPmaxNotReady();
        } catch (RuntimeException e) {
            log.error("partition prebuild failed", e);
        } finally {
            cronLock.unlock(JOB);
        }
    }

    private void ensure(YearMonth month, List<String> existing) {
        String partition = CronSql.nextPartitionName(month);
        if (existing.contains(partition)) {
            log.info("partition already exists, partition={}", partition);
            return;
        }
        String boundary = CronSql.requireBoundary(CronSql.valuesLessThan(month));
        partitionMapper.reorganizeAddPartition(CronSql.requirePartition(partition), boundary);
        log.info("partition created, partition={}, lessThan={}", partition, boundary);
    }

    private void warnPmaxNotReady() {
        long pmaxRows = partitionMapper.countRowsIn(CronSql.PMAX);
        if (pmaxRows > 0) {
            log.error("pmax partition is not empty, partition prebuild missed for some month, rows={}", pmaxRows);
        }
    }
}
