package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncProgressLogBufferTest {

    @Test
    void append_andSince_returnsIncrementalLogs() {
        SyncProgressLogBuffer buffer = new SyncProgressLogBuffer();
        long taskId = 42L;

        buffer.append(taskId, "info", "line1");
        buffer.append(taskId, "success", "line2");

        List<Map<String, Object>> all = buffer.since(taskId, 0);
        assertEquals(2, all.size());
        assertEquals(1L, all.get(0).get("seq"));
        assertEquals("line1", all.get(0).get("msg"));
        assertEquals("success", all.get(1).get("type"));

        List<Map<String, Object>> delta = buffer.since(taskId, 1);
        assertEquals(1, delta.size());
        assertEquals("line2", delta.get(0).get("msg"));
    }

    @Test
    void clear_resetsTaskLogs() {
        SyncProgressLogBuffer buffer = new SyncProgressLogBuffer();
        long taskId = 7L;
        buffer.append(taskId, "info", "before");
        buffer.clear(taskId);
        buffer.append(taskId, "info", "after");

        List<Map<String, Object>> logs = buffer.since(taskId, 0);
        assertEquals(1, logs.size());
        assertEquals("after", logs.get(0).get("msg"));
    }

    @Test
    void since_unknownTask_returnsEmpty() {
        SyncProgressLogBuffer buffer = new SyncProgressLogBuffer();
        assertTrue(buffer.since(999L, 0).isEmpty());
    }
}
