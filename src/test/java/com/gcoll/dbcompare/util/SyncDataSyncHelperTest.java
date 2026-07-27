package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncDataSyncHelperTest {

    @Test
    void primaryKeyIndexes_caseInsensitive() {
        int[] idx = SyncDataSyncHelper.primaryKeyIndexes(
                Arrays.asList("id", "name", "amt"),
                Collections.singletonList("ID"));
        assertArrayEquals(new int[]{0}, idx);
    }

    @Test
    void comparePrimaryKeys_ordersRows() {
        int[] pk = {0};
        Object[] a = {1, "a"};
        Object[] b = {2, "b"};
        Object[] c = {1, "x"};
        assertTrue(SyncDataSyncHelper.comparePrimaryKeys(a, b, pk) < 0);
        assertTrue(SyncDataSyncHelper.comparePrimaryKeys(b, a, pk) > 0);
        assertEquals(0, SyncDataSyncHelper.comparePrimaryKeys(a, c, pk));
    }

    @Test
    void nonPrimaryEquals_detectsDiff() {
        int[] pk = {0};
        assertTrue(SyncDataSyncHelper.nonPrimaryEquals(
                new Object[]{1, "n", 3}, new Object[]{1, "n", 3}, pk));
        assertFalse(SyncDataSyncHelper.nonPrimaryEquals(
                new Object[]{1, "n", 3}, new Object[]{1, "n", 4}, pk));
    }

    @Test
    void pkMapKey_stableForInLookup() {
        assertEquals("1\u00012", SyncDataSyncHelper.pkMapKey(new Object[]{1, "x", 2}, new int[]{0, 2}));
        assertEquals(SyncDataSyncHelper.pkMapKey(new Object[]{9L, "a"}, new int[]{0, 1}),
                SyncDataSyncHelper.pkValuesMapKey(new Object[]{9L, "a"}));
    }
}
