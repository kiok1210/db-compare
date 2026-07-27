package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncRecordChannelTest {

    @Test
    void putAndTake_deliversBatchesThenEnd() throws Exception {
        SyncRecordChannel ch = new SyncRecordChannel(2);
        List<Object[]> batch = Arrays.asList(new Object[]{1}, new Object[]{2});
        ch.putBatch(batch);
        ch.putEnd();
        assertEquals(batch, ch.take());
        assertSame(SyncRecordChannel.END, ch.take());
    }

    @Test
    void fail_surfacesOnThrowIfFailed() {
        SyncRecordChannel ch = new SyncRecordChannel(1);
        ch.fail(new IllegalStateException("writer down"));
        Exception ex = assertThrows(Exception.class, ch::throwIfFailed);
        assertTrue(ex.getMessage().contains("writer down")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("writer down"))
                || ex instanceof IllegalStateException);
    }

    @Test
    void streamChannelThreshold_belowMillionLarge() {
        assertTrue(SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD
                < SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD);
        assertEquals(100_000L, SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD);
    }
}
