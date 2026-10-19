package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.service.datax.DataxLogMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTaskRunnerDataxProgressTest {

    @Test
    void calcDataxWithinTablePct_clearPhase_increasesWithTime() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.clearTargetRows = 9_000_000L;
        snap.sourceTotalRows = 18_000_000L;
        long start = System.currentTimeMillis() - 60_000L; // 估删约 30%
        int pct = SyncTaskRunner.calcDataxWithinTablePct(snap, start, true);
        // 清表阶段本表% = 清表完成度，不再被源行数加权压低
        assertTrue(pct >= 25 && pct <= 40, "expected clear≈30%, got " + pct);
    }

    @Test
    void calcDataxWithinTablePct_clearPhase_usesRemainingRows() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.clearTargetRows = 10_000_000L;
        snap.sourceTotalRows = 10_000_000L;
        snap.clearRemainingRows = 2_000_000L; // 已删 80%
        int pct = SyncTaskRunner.calcDataxWithinTablePct(snap, System.currentTimeMillis(), true);
        // 本表% = 清表 80%（不再 clearW=0.5 → 40%）
        assertTrue(pct >= 75 && pct <= 85, "got " + pct);
    }

    @Test
    void calcDataxWithinTablePct_overEstimate_creepsPast90() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.clearTargetRows = 1_000_000L;
        snap.sourceTotalRows = 1_000_000L;
        // 预估约 22s；已跑 30 分钟应 >90%
        long start = System.currentTimeMillis() - 1_800_000L;
        double frac = SyncTaskRunner.estimateClearFraction(snap, 1_000_000L, start);
        assertTrue(frac > 0.90d && frac <= 0.98d, "got " + frac);
        int pct = SyncTaskRunner.calcDataxWithinTablePct(snap, start, true);
        assertTrue(pct >= 90 && pct <= 99, "本表应跟上清表爬升, got " + pct);
    }

    @Test
    void calcDataxWithinTablePct_writePhase_usesSourceCount() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.clearTargetRows = 10_000_000L;
        snap.sourceTotalRows = 10_000_000L;
        snap.sawProgress = true;
        snap.totalRecords = 5_000_000L;
        int pct = SyncTaskRunner.calcDataxWithinTablePct(snap, System.currentTimeMillis(), true);
        // 写入：50 + 0.5*49 ≈ 75
        assertTrue(pct >= 70 && pct <= 80, "got " + pct);
    }

    @Test
    void formatHint_showsClearedRateAndMbHint() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.clearRemainingRows = 3_000_000L;
        snap.clearRecentRowsPerSec = 12_000L;
        String hint = SyncTaskRunner.formatDataxProgressHint(
                snap, 70, true, 10_000_000L, 60_000_000L, System.currentTimeMillis() - 30_000L);
        assertTrue(hint.contains("已删约 7000000/10000000"), hint);
        assertTrue(hint.contains("剩余 3000000"), hint);
        assertTrue(hint.contains("均速"), hint);
        assertTrue(hint.contains("近段 12000 行/秒"), hint);
        assertTrue(hint.contains("MB/s"), hint);
    }

    @Test
    void formatClearRateText_avgOnly() {
        String s = SyncTaskRunner.formatClearRateText(30_000L, 10L, -1L);
        assertTrue(s.contains("均速 3000 行/秒") && !s.contains("近段"), s);
    }
}
