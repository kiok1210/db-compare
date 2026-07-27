package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxWriteModeTest {

    @Test
    void normalize_defaultsToInsert() {
        assertEquals(DataxWriteMode.INSERT, DataxWriteMode.normalize(null));
        assertEquals(DataxWriteMode.INSERT, DataxWriteMode.normalize(""));
        assertEquals(DataxWriteMode.INSERT, DataxWriteMode.normalize("insert"));
        assertEquals(DataxWriteMode.INSERT, DataxWriteMode.normalize("INSERT"));
    }

    @Test
    void normalize_acceptsReplace() {
        assertEquals(DataxWriteMode.REPLACE, DataxWriteMode.normalize("replace"));
        assertEquals(DataxWriteMode.REPLACE, DataxWriteMode.normalize("REPLACE"));
        assertEquals(DataxWriteMode.REPLACE, DataxWriteMode.normalize("replace into"));
    }

    @Test
    void displayName_mentionsMode() {
        assertTrue(DataxWriteMode.displayName("insert").contains("insert"));
        assertTrue(DataxWriteMode.displayName("replace").contains("replace"));
    }
}
