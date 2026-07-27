package com.gcoll.dbcompare.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
