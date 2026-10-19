package com.gcoll.dbcompare.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTaskParallelTest {

    @Test
    void clampParallelThreads_capsAt20() {
        assertEquals(8, SyncTask.clampParallelThreads(null));
        assertEquals(8, SyncTask.clampParallelThreads(0));
        assertEquals(1, SyncTask.clampParallelThreads(1));
        assertEquals(20, SyncTask.clampParallelThreads(20));
        assertEquals(20, SyncTask.clampParallelThreads(50));
    }

    @Test
    void defaultParallelForTableCount_matchesTablesCappedAt20() {
        assertEquals(1, SyncTask.defaultParallelForTableCount(0));
        assertEquals(1, SyncTask.defaultParallelForTableCount(-3));
        assertEquals(5, SyncTask.defaultParallelForTableCount(5));
        assertEquals(20, SyncTask.defaultParallelForTableCount(20));
        assertEquals(20, SyncTask.defaultParallelForTableCount(100));
    }

    @Test
    void clampClearParallel_capsAt8() {
        assertEquals(4, SyncTask.clampClearParallel(null));
        assertEquals(4, SyncTask.clampClearParallel(0));
        assertEquals(1, SyncTask.clampClearParallel(1));
        assertEquals(8, SyncTask.clampClearParallel(8));
        assertEquals(8, SyncTask.clampClearParallel(20));
    }

    @Test
    void dataxClearMode_setterNormalizes() {
        SyncTask t = new SyncTask();
        t.setDataxClearMode("parallel_delete");
        assertEquals("PARALLEL_DELETE", t.getDataxClearMode());
        t.setDataxClearMode("  ");
        assertEquals(null, t.getDataxClearMode());
    }
}
