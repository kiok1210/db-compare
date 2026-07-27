package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncUpsertHelperTest {

    @Test
    void buildMysqlOnDuplicateUpsert_updatesNonPkColumns() {
        String sql = SyncUpsertHelper.buildMysqlOnDuplicateUpsert(
                "`prd`.`t`",
                Arrays.asList("id", "name", "amt"),
                Collections.singletonList("id"),
                DatabaseType.MYSQL);
        assertTrue(sql.startsWith("INSERT INTO `prd`.`t` (`id`, `name`, `amt`) VALUES (?, ?, ?)"), sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
        assertTrue(sql.contains("`name`=VALUES(`name`)"), sql);
        assertTrue(sql.contains("`amt`=VALUES(`amt`)"), sql);
        assertFalse(sql.contains("`id`=VALUES(`id`)"), sql);
    }

    @Test
    void nonPrimaryColumns_filtersPk() {
        List<String> cols = SyncUpsertHelper.nonPrimaryColumns(
                Arrays.asList("ID", "name", "x"),
                Collections.singletonList("id"));
        assertEquals(Arrays.asList("name", "x"), cols);
    }
}
