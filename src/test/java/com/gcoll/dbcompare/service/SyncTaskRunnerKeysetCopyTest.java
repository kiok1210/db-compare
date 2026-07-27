package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTaskRunnerKeysetCopyTest {

    @Test
    void canUseKeysetPaging_requiresPkInColumnsAndMysqlFamily() {
        List<String> cols = Arrays.asList("id", "name", "amt");
        assertTrue(SyncTaskRunner.canUseKeysetPaging(cols, Collections.singletonList("id"), DatabaseType.MYSQL));
        assertTrue(SyncTaskRunner.canUseKeysetPaging(cols, Collections.singletonList("ID"), DatabaseType.GOLDENDB));
        assertFalse(SyncTaskRunner.canUseKeysetPaging(cols, Collections.singletonList("missing"), DatabaseType.MYSQL));
        assertFalse(SyncTaskRunner.canUseKeysetPaging(cols, Collections.emptyList(), DatabaseType.MYSQL));
        assertFalse(SyncTaskRunner.canUseKeysetPaging(cols, Collections.singletonList("id"), DatabaseType.ORACLE));
    }

    @Test
    void buildKeysetSelectSql_firstAndNextPage() {
        List<String> pk = Collections.singletonList("`id`");
        assertEquals(
                "SELECT `id`,`n` FROM `t` ORDER BY `id` LIMIT ?",
                SyncTaskRunner.buildKeysetSelectSql("`id`,`n`", "`t`", pk, true));
        assertEquals(
                "SELECT `id`,`n` FROM `t` WHERE (`id`) > (?) ORDER BY `id` LIMIT ?",
                SyncTaskRunner.buildKeysetSelectSql("`id`,`n`", "`t`", pk, false));
    }

    @Test
    void buildKeysetSelectSql_compositePk() {
        List<String> pk = Arrays.asList("`a`", "`b`");
        assertEquals(
                "SELECT * FROM t WHERE (`a`, `b`) > (?, ?) ORDER BY `a`, `b` LIMIT ?",
                SyncTaskRunner.buildKeysetSelectSql("*", "t", pk, false));
    }

    @Test
    void buildPkInSelectSql_singleAndComposite() {
        assertEquals(
                "SELECT `id`,`n` FROM `t` WHERE (`id`) IN ((?), (?))",
                SyncTaskRunner.buildPkInSelectSql("`id`,`n`", "`t`",
                        Collections.singletonList("`id`"), 2));
        assertEquals(
                "SELECT * FROM t WHERE (`a`, `b`) IN ((?, ?), (?, ?), (?, ?))",
                SyncTaskRunner.buildPkInSelectSql("*", "t",
                        Arrays.asList("`a`", "`b`"), 3));
    }

    @Test
    void extractPkValues_usesIndexes() {
        int[] idx = SyncTaskRunner.resolvePkIndexes(Arrays.asList("a", "b", "c"), Arrays.asList("c", "a"));
        assertArrayEquals(new int[]{2, 0}, idx);
        Object[] pk = SyncTaskRunner.extractPkValues(new Object[]{"A", "B", 9L}, idx);
        assertArrayEquals(new Object[]{9L, "A"}, pk);
    }
}
