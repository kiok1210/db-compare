package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxClearModeTest {

    @Test
    void normalize_defaultsToAuto() {
        assertEquals(DataxClearMode.AUTO, DataxClearMode.normalize(null));
        assertEquals(DataxClearMode.AUTO, DataxClearMode.normalize(""));
        assertEquals(DataxClearMode.DELETE, DataxClearMode.normalize("delete"));
        assertEquals(DataxClearMode.DATAX_DELETE, DataxClearMode.normalize("datax_delete"));
        assertEquals(DataxClearMode.DATAX_TRUNCATE, DataxClearMode.normalize("DATAX_TRUNCATE"));
    }

    @Test
    void buildPreSql_truncateAndDelete() {
        assertEquals("TRUNCATE TABLE `t1`", DataxClearMode.buildPreSql("t1", DataxClearMode.TRUNCATE));
        assertEquals("DELETE FROM `t1`", DataxClearMode.buildPreSql("t1", DataxClearMode.DELETE));
        assertNull(DataxClearMode.buildPreSql("t1", DataxClearMode.AUTO));
        assertNull(DataxClearMode.buildPreSql("t1", DataxClearMode.NONE));
    }

    @Test
    void buildPreSql_dataxDelete_qualifiedTable() {
        assertEquals("DELETE FROM `prd_gcoll_aux`.`customer_payments`",
                DataxClearMode.buildPreSql("prd_gcoll_aux.customer_payments", DataxClearMode.DATAX_DELETE));
        assertEquals("TRUNCATE TABLE `prd_gcoll_aux`.`customer_payments`",
                DataxClearMode.buildPreSql("prd_gcoll_aux.customer_payments", DataxClearMode.DATAX_TRUNCATE));
    }

    @Test
    void sideClear_flags() {
        assertTrue(DataxClearMode.isAppSideClear(DataxClearMode.AUTO));
        assertTrue(DataxClearMode.isAppSideClear(DataxClearMode.DELETE));
        assertFalse(DataxClearMode.isAppSideClear(DataxClearMode.DATAX_DELETE));
        assertTrue(DataxClearMode.isDataxSideClear(DataxClearMode.DATAX_DELETE));
        assertTrue(DataxClearMode.isDataxSideClear(DataxClearMode.DATAX_TRUNCATE));
        assertFalse(DataxClearMode.isDataxSideClear(DataxClearMode.NONE));
    }

    @Test
    void displayName_mentionsDataxDelete() {
        assertTrue(DataxClearMode.displayName(DataxClearMode.DATAX_DELETE).contains("DataX"));
        assertTrue(DataxClearMode.displayName(DataxClearMode.DELETE).contains("分批")
                || DataxClearMode.displayName(DataxClearMode.DELETE).contains("DELETE"));
    }
}
