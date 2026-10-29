package com.example.device.ingest.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * 分区名与边界的生成，以及拼进 SQL 前的注入拒绝。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class CronSqlTest {

    @Test
    void partitionNameFollowsYearMonth() {
        assertEquals("p202611", CronSql.nextPartitionName(YearMonth.of(2026, 11)));
        assertEquals("p202701", CronSql.nextPartitionName(YearMonth.of(2027, 1)));
    }

    @Test
    void boundaryIsFirstDayOfNextMonthIncludingYearRollover() {
        assertEquals("2026-12-01", CronSql.valuesLessThan(YearMonth.of(2026, 11)));
        assertEquals("2027-01-01", CronSql.valuesLessThan(YearMonth.of(2026, 12)));
        assertEquals("2028-03-01", CronSql.valuesLessThan(YearMonth.of(2028, 2)));
    }

    @Test
    void partitionNameIsValidatedBeforeSqlConcatenation() {
        assertEquals("p202611", CronSql.requirePartition("p202611"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requirePartition("pmax"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requirePartition("p20261"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requirePartition("p202611; DROP TABLE t"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requirePartition(null));
    }

    @Test
    void boundaryIsValidatedBeforeSqlConcatenation() {
        assertEquals("2026-11-01", CronSql.requireBoundary("2026-11-01"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requireBoundary("2026-11-1"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requireBoundary("2026-11-01'"));
        assertThrows(IllegalArgumentException.class, () -> CronSql.requireBoundary(null));
    }

    @Test
    void monthOfRoundTripsPartitionName() {
        assertEquals(YearMonth.of(2026, 11), CronSql.monthOf("p202611"));
        assertNull(CronSql.monthOf(CronSql.PMAX));
        assertNull(CronSql.monthOf(null));
        assertNull(CronSql.monthOf("p2026"));
    }
}
