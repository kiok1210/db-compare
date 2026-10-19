package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncHybridRouterTest {

    @Test
    void shouldUseDatax_belowThreshold_false() {
        assertFalse(SyncHybridRouter.shouldUseDatax(999_999L, "any"));
        assertFalse(SyncHybridRouter.shouldUseDatax(0L, "any"));
        assertFalse(SyncHybridRouter.shouldUseDatax(-1L, "any"));
    }

    @Test
    void shouldUseDatax_atThreshold_withoutConfig_false() {
        assertFalse(SyncHybridRouter.shouldUseDatax(SyncHybridRouter.DATAX_ROW_THRESHOLD, ""));
        assertFalse(SyncHybridRouter.shouldUseDatax(SyncHybridRouter.DATAX_ROW_THRESHOLD, null));
        assertFalse(SyncHybridRouter.shouldUseDatax(2_000_000L, "D:/not-exist-datax"));
    }

    @Test
    void shouldUseDatax_atThreshold_withConfig_true(@TempDir Path temp) throws Exception {
        Path bin = temp.resolve("bin");
        Files.createDirectories(bin);
        Path py = bin.resolve("datax.py");
        Files.write(py, "# mock".getBytes());
        assertTrue(SyncHybridRouter.isDataxConfigured(temp.toString()));
        assertTrue(SyncHybridRouter.shouldUseDatax(SyncHybridRouter.DATAX_ROW_THRESHOLD, temp.toString()));
        assertTrue(SyncHybridRouter.shouldUseDatax(5_000_000L, temp.toString()));
    }

    @Test
    void isDataxConfigured_empty_false() {
        assertFalse(SyncHybridRouter.isDataxConfigured(null));
        assertFalse(SyncHybridRouter.isDataxConfigured("  "));
    }

    @Test
    void describeDecision_coversBranches() {
        assertTrue(SyncHybridRouter.describeDecision(-1, false, false).contains("未知"));
        assertTrue(SyncHybridRouter.describeDecision(100, false, true).contains("普通"));
        assertTrue(SyncHybridRouter.describeDecision(2_000_000L, true, true).contains("DataX"));
        assertTrue(SyncHybridRouter.describeDecision(2_000_000L, false, false).contains("未配置"));
    }
}
