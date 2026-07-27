package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskNameHelperTest {

    @Test
    void stripReExecuteSuffix_removesStackedSuffixes() {
        String stacked = "生产环境信创比对-0721 - 重新执行 - 重新执行 - 重新执行 - 重新执行";
        assertEquals("生产环境信创比对-0721", TaskNameHelper.stripReExecuteSuffix(stacked));
    }

    @Test
    void stripReExecuteSuffix_removesTimestampVariant() {
        String name = "生产环境信创比对-0721 - 重新执行 07-23 17:01";
        assertEquals("生产环境信创比对-0721", TaskNameHelper.stripReExecuteSuffix(name));
    }

    @Test
    void buildReExecuteName_doesNotStack() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 23, 17, 5);
        String fromStacked = TaskNameHelper.buildReExecuteName(
                "生产环境信创比对-0721 - 重新执行 - 重新执行 - 重新执行", at);
        assertEquals("生产环境信创比对-0721 - 重新执行 07-23 17:05", fromStacked);

        String again = TaskNameHelper.buildReExecuteName(fromStacked, at.plusMinutes(3));
        assertEquals("生产环境信创比对-0721 - 重新执行 07-23 17:08", again);
        assertFalse(again.contains("重新执行 - 重新执行"));
    }

    @Test
    void buildReExecuteName_blank_usesDefault() {
        String name = TaskNameHelper.buildReExecuteName("  ", LocalDateTime.of(2026, 1, 2, 3, 4));
        assertTrue(name.startsWith("未命名任务 - 重新执行 "));
        assertEquals("未命名任务 - 重新执行 01-02 03:04", name);
    }
}
