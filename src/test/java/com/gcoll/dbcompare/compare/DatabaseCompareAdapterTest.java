package com.gcoll.dbcompare.compare;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCompareAdapterTest {

    @Test
    void compareRows_identicalRows() {
        Map<String, Object> row1 = row("id", 1L, "name", "alice");
        Map<String, Object> row2 = row("id", 1L, "name", "alice");

        assertTrue(DatabaseCompareAdapter.compareRows(row1, row2));
    }

    @Test
    void compareRows_differentValue() {
        Map<String, Object> row1 = row("id", 1L, "name", "alice");
        Map<String, Object> row2 = row("id", 1L, "name", "bob");

        assertFalse(DatabaseCompareAdapter.compareRows(row1, row2));
    }

    @Test
    void compareRows_nullMismatch() {
        Map<String, Object> row1 = row("id", 1L, "name", null);
        Map<String, Object> row2 = row("id", 1L, "name", "bob");

        assertFalse(DatabaseCompareAdapter.compareRows(row1, row2));
    }

    @Test
    void compareRows_bothNull_sameKey() {
        Map<String, Object> row1 = row("id", 1L, "name", null);
        Map<String, Object> row2 = row("id", 1L, "name", null);

        assertTrue(DatabaseCompareAdapter.compareRows(row1, row2));
    }

    @Test
    void compareRows_differentSize() {
        Map<String, Object> row1 = row("id", 1L);
        Map<String, Object> row2 = row("id", 1L, "name", "alice");

        assertFalse(DatabaseCompareAdapter.compareRows(row1, row2));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
