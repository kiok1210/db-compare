package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncSelectCapWarnTest {

    @Test
    void isSuspiciousSelectCap_hitsCommonLimits() {
        assertTrue(SyncTaskRunner.isSuspiciousSelectCap(100_000L));
        assertTrue(SyncTaskRunner.isSuspiciousSelectCap(20_000L));
        assertFalse(SyncTaskRunner.isSuspiciousSelectCap(100_001L));
        assertFalse(SyncTaskRunner.isSuspiciousSelectCap(146_478L));
        assertFalse(SyncTaskRunner.isSuspiciousSelectCap(0L));
    }
}
