package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxClearModeTest {

    @Test
    void normalize_defaultsToAuto() {
        assertEquals(DataxClearMode.AUTO, DataxClearMode.normalize(null));
        assertEquals(DataxClearMode.DATAX_DELETE, DataxClearMode.normalize("datax_delete"));
        assertEquals(DataxClearMode.DATAX_TRUNCATE, DataxClearMode.normalize("DATAX_TRUNCATE"));
    }

    @Test
    void buildPreSqlList_dataxDelete_usesTargetCount_notSource() {
        // 目标 25M → 批次数按目标算；与源 60M 无关
        List<String> list = DataxClearMode.buildPreSqlList(
                "prd_gcoll_aux.customer_payments", DataxClearMode.DATAX_DELETE, 25_115_637L, 50_000);
        int expect = DataxClearMode.calcBatchCount(25_115_637L, 50_000);
        assertEquals(expect, list.size());
        assertTrue(expect > 100);
        String expected = "DELETE FROM `prd_gcoll_aux`.`customer_payments` LIMIT 50000";
        assertEquals(expected, list.get(0));
        assertFalse(list.get(0).matches("DELETE FROM .*`\\s*$"));
    }

    @Test
    void buildPreSqlList_zeroTarget_empty() {
        assertTrue(DataxClearMode.buildPreSqlList("t", DataxClearMode.DATAX_DELETE, 0, 50000).isEmpty());
    }

    @Test
    void sideClear_dataxDeleteIsDataxSide() {
        assertFalse(DataxClearMode.isAppSideClear(DataxClearMode.DATAX_DELETE));
        assertTrue(DataxClearMode.isDataxSideClear(DataxClearMode.DATAX_DELETE));
        assertTrue(DataxClearMode.isAppSideClear(DataxClearMode.DELETE));
    }

    @Test
    void buildPreSql_truncate() {
        assertEquals("TRUNCATE TABLE `t1`", DataxClearMode.buildPreSql("t1", DataxClearMode.TRUNCATE));
        assertNull(DataxClearMode.buildPreSql("t1", DataxClearMode.AUTO));
    }

    @Test
    void normalize_parallelDelete() {
        assertEquals(DataxClearMode.PARALLEL_DELETE, DataxClearMode.normalize("parallel_delete"));
        assertTrue(DataxClearMode.isAppSideClear(DataxClearMode.PARALLEL_DELETE));
        assertFalse(DataxClearMode.isDataxSideClear(DataxClearMode.PARALLEL_DELETE));
        assertTrue(DataxClearMode.displayName(DataxClearMode.PARALLEL_DELETE).contains("并行"));
    }

    @Test
    void resolve_taskOverridesSettings() {
        assertEquals(DataxClearMode.PARALLEL_DELETE,
                DataxClearMode.resolve("PARALLEL_DELETE", "DATAX_DELETE"));
        assertEquals(DataxClearMode.DATAX_DELETE,
                DataxClearMode.resolve(null, "DATAX_DELETE"));
        assertEquals(DataxClearMode.AUTO, DataxClearMode.resolve("", null));
    }

    @Test
    void splitPkRanges_coversMinMax() {
        List<long[]> ranges = DataxClearMode.splitPkRanges(1, 10, 4);
        assertEquals(4, ranges.size());
        assertEquals(1L, ranges.get(0)[0]);
        assertEquals(11L, ranges.get(3)[1]); // exclusive end past max
        long covered = 0;
        for (long[] r : ranges) {
            covered += r[1] - r[0];
        }
        assertEquals(10L, covered);
    }

    @Test
    void buildPreSqlList_parallelDelete_empty() {
        assertTrue(DataxClearMode.buildPreSqlList("t", DataxClearMode.PARALLEL_DELETE, 1_000_000, 50000).isEmpty());
    }
}
