package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncIdleWatchdogTest {

    @Test
    void abortIdleTables_afterTableIdle_forceClosesAndMarks() {
        SyncIdleWatchdog wd = new SyncIdleWatchdog(50L, 5_000L);
        AtomicInteger closed = new AtomicInteger();
        wd.register("t1", closed::incrementAndGet);
        wd.touch("t1");

        long t0 = wd.lastTaskActivityMs();
        List<String> first = wd.abortIdleTables(t0 + 10L, null);
        assertTrue(first.isEmpty());
        assertEquals(0, closed.get());

        List<String> hit = new ArrayList<>();
        List<String> second = wd.abortIdleTables(t0 + 80L, hit::add);
        assertEquals(1, second.size());
        assertEquals("t1", second.get(0));
        assertEquals(1, hit.size());
        assertTrue(wd.isTableTimedOut("t1"));
        assertEquals(1, closed.get());
        assertTrue(wd.tableTimedOutAtMs("t1") > 0);

        // 不会重复触发
        assertTrue(wd.abortIdleTables(t0 + 200L, hit::add).isEmpty());
        assertEquals(1, closed.get());
    }

    @Test
    void abortIdleTask_afterTaskIdle_marksAndClosesAll() {
        SyncIdleWatchdog wd = new SyncIdleWatchdog(30L, 100L);
        AtomicInteger closed = new AtomicInteger();
        wd.register("a", closed::incrementAndGet);
        wd.register("b", closed::incrementAndGet);
        long t0 = wd.lastTaskActivityMs();

        assertFalse(wd.abortIdleTask(t0 + 50L, null));
        assertFalse(wd.isTaskTimedOut());

        AtomicInteger onTask = new AtomicInteger();
        assertTrue(wd.abortIdleTask(t0 + 150L, onTask::incrementAndGet));
        assertTrue(wd.isTaskTimedOut());
        assertEquals(1, onTask.get());
        assertEquals(2, closed.get());
        assertTrue(wd.taskTimedOutAtMs() > 0);

        assertFalse(wd.abortIdleTask(t0 + 500L, onTask::incrementAndGet));
        assertEquals(1, onTask.get());
    }

    @Test
    void touch_keepsTableAliveUntilIdleWindowElapses() {
        SyncIdleWatchdog wd = new SyncIdleWatchdog(80L, 500L);
        wd.register("t", () -> { });
        wd.touch("t");
        long base = wd.lastTaskActivityMs();
        assertTrue(wd.abortIdleTables(base + 40L, null).isEmpty());
        wd.touch("t");
        base = wd.lastTaskActivityMs();
        assertTrue(wd.abortIdleTables(base + 40L, null).isEmpty());
        assertEquals(1, wd.abortIdleTables(base + 100L, null).size());
        assertTrue(wd.isTableTimedOut("t"));
    }

    @Test
    void markLargeTable_onlyRelaxesThatTableIdle() {
        SyncIdleWatchdog wd = new SyncIdleWatchdog(50L, 100L);
        wd.register("small", () -> { });
        wd.register("huge", () -> { });
        assertTrue(wd.markLargeTable("huge"));
        assertTrue(wd.isLargeTable("huge"));
        assertFalse(wd.isLargeTable("small"));
        assertEquals(SyncIdleWatchdog.LARGE_TABLE_IDLE_MS, wd.tableIdleLimitMs("huge"));
        assertEquals(50L, wd.tableIdleLimitMs("small"));
        assertEquals(SyncIdleWatchdog.LARGE_TASK_IDLE_MS, wd.taskIdleLimitMs());

        long t0 = wd.lastTaskActivityMs();
        // 普通表 50ms 超时；超大表仍存活
        List<String> hit = wd.abortIdleTables(t0 + 80L, null);
        assertEquals(1, hit.size());
        assertEquals("small", hit.get(0));
        assertFalse(wd.isTableTimedOut("huge"));
        assertTrue(wd.abortIdleTables(t0 + SyncIdleWatchdog.LARGE_TABLE_IDLE_MS + 10L, null)
                .contains("huge"));
    }

    @Test
    void markLargeTable_secondCall_notNewlyMarked() {
        SyncIdleWatchdog wd = new SyncIdleWatchdog();
        assertTrue(wd.markLargeTable("t"));
        assertFalse(wd.markLargeTable("t"));
    }

    @Test
    void forTarget_goldenDb_usesSlowLimits() {
        SyncIdleWatchdog wd = SyncIdleWatchdog.forTarget(DatabaseType.GOLDENDB);
        assertEquals(SyncIdleWatchdog.SLOW_TARGET_TABLE_IDLE_MS, wd.configuredTableIdleMs());
        assertEquals(SyncIdleWatchdog.SLOW_TARGET_TASK_IDLE_MS, wd.configuredTaskIdleMs());
        assertEquals(SyncIdleWatchdog.SLOW_TARGET_TABLE_IDLE_MS, wd.tableIdleLimitMs("any"));
    }

    @Test
    void forTarget_other_usesDefaultLimits() {
        SyncIdleWatchdog wd = SyncIdleWatchdog.forTarget(DatabaseType.MYSQL);
        assertEquals(SyncIdleWatchdog.DEFAULT_TABLE_IDLE_MS, wd.configuredTableIdleMs());
        assertEquals(SyncIdleWatchdog.DEFAULT_TASK_IDLE_MS, wd.configuredTaskIdleMs());
    }

    @Test
    void dataSyncHeavyThreshold_lowerThanMillionRowLarge() {
        assertTrue(SyncIdleWatchdog.DATA_SYNC_HEAVY_ROW_THRESHOLD
                < SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD);
        assertEquals(50_000L, SyncIdleWatchdog.DATA_SYNC_HEAVY_ROW_THRESHOLD);
    }

    @Test
    void sixtyMillionRows_triggersStreamChannelAndLargeWatchdog() {
        long sixtyM = 60_000_000L;
        assertTrue(sixtyM >= SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD);
        assertTrue(sixtyM >= SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD);
        SyncIdleWatchdog wd = new SyncIdleWatchdog();
        assertTrue(wd.markLargeTable("loadtest.big_sync_60m"));
        assertEquals(SyncIdleWatchdog.LARGE_TABLE_IDLE_MS,
                wd.tableIdleLimitMs("loadtest.big_sync_60m"));
    }
}
