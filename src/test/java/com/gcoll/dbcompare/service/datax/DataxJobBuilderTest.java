package com.gcoll.dbcompare.service.datax;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataxJobBuilderTest {

    @TempDir
    Path temp;

    @Test
    void buildMysqlFullCopyJob_containsTruncateAndSplitPk() throws Exception {
        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                "jdbc:mysql://127.0.0.1:3306/gcoll_aux", "u1", "p1", "customer_payments",
                "jdbc:mysql://127.0.0.1:3306/prd_gcoll_aux", "u2", "p2", "customer_payments",
                "id", 8, 2048, DataxClearMode.TRUNCATE);
        Path file = DataxJobBuilder.writeJobFile(temp, "customer_payments.json", job);
        String json = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(json.contains("mysqlreader"));
        assertTrue(json.contains("mysqlwriter"));
        assertTrue(json.contains("TRUNCATE TABLE `customer_payments`"));
        assertTrue(json.contains("\"splitPk\" : \"id\"") || json.contains("\"splitPk\":\"id\""));
        assertTrue(json.contains("customer_payments"));
    }

    @Test
    void buildMysqlFullCopyJob_dataxDelete_preSqlByTargetCount() throws Exception {
        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                "jdbc:mysql://127.0.0.1:3306/a", "u", "p", "a.t",
                "jdbc:mysql://127.0.0.1:3306/b", "u", "p", "b.t",
                "id", 4, 1024, DataxClearMode.DATAX_DELETE, DataxWriteMode.INSERT,
                120_000L, 50_000);
        String json = new String(Files.readAllBytes(
                DataxJobBuilder.writeJobFile(temp, "dxdel.json", job)), "UTF-8");
        assertTrue(json.contains("preSql"), json);
        assertTrue(json.contains("DELETE FROM `b`.`t` LIMIT 50000"), json);
        int count = 0;
        int from = 0;
        String needle = "DELETE FROM `b`.`t` LIMIT 50000";
        while ((from = json.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        assertEquals(8, count, json);
        assertTrue(json.contains("mysqlreader"), json);
    }

    @Test
    void buildMysqlFullCopyJob_deleteMode_noPreSql() throws Exception {
        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                "jdbc:mysql://127.0.0.1:3306/a", "u", "p", "t",
                "jdbc:mysql://127.0.0.1:3306/b", "u", "p", "t",
                null, 4, 1024, DataxClearMode.DELETE, DataxWriteMode.INSERT, 100_000L, 50_000);
        String json = new String(Files.readAllBytes(
                DataxJobBuilder.writeJobFile(temp, "t.json", job)), "UTF-8");
        assertFalse(json.contains("preSql"), json);
    }

    @Test
    void buildMysqlFullCopyJob_auto_noPreSql() throws Exception {
        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                "jdbc:mysql://127.0.0.1:3306/a", "u", "p", "t",
                "jdbc:mysql://127.0.0.1:3306/b", "u", "p", "t",
                "id", 4, 1024, DataxClearMode.NONE);
        String json = new String(Files.readAllBytes(
                DataxJobBuilder.writeJobFile(temp, "n.json", job)), "UTF-8");
        assertTrue(!json.contains("preSql"));
        assertTrue(json.contains("\"writeMode\" : \"insert\"") || json.contains("\"writeMode\":\"insert\""));
    }

    @Test
    void ensureCatalogInUrl_fillsMissingDb() {
        String url = "jdbc:mysql://127.0.0.1:3306/?useSSL=false";
        String filled = DataxSyncService.ensureCatalogInUrl(url, "gcoll_aux", DatabaseType.MYSQL);
        assertTrue(filled.contains("/gcoll_aux?"), filled);
    }

    @Test
    void ensureCatalogInUrl_hostPortQuery_noSlashBeforeParams() {
        // 旧 bug：拼成 jdbc:mysql://host:3306?x/gcoll_aux
        String url = "jdbc:mysql://127.0.0.1:3306?useSSL=false";
        String filled = DataxSyncService.ensureCatalogInUrl(url, "gcoll_aux", DatabaseType.MYSQL);
        assertEquals("jdbc:mysql://127.0.0.1:3306/gcoll_aux?useSSL=false", filled);
    }

    @Test
    void ensureCatalogInUrl_replacesExistingDb() {
        String url = "jdbc:mysql://127.0.0.1:3306/other_db?useSSL=false";
        String filled = DataxSyncService.ensureCatalogInUrl(url, "gcoll_aux", DatabaseType.MYSQL);
        assertEquals("jdbc:mysql://127.0.0.1:3306/gcoll_aux?useSSL=false", filled);
    }

    @Test
    void qualifyMysqlTable_addsCatalog() {
        assertEquals("gcoll_aux.customer_payments",
                DataxSyncService.qualifyMysqlTable("gcoll_aux", "customer_payments"));
        assertEquals("gcoll_aux.customer_payments",
                DataxSyncService.qualifyMysqlTable("gcoll_aux", "gcoll_aux.customer_payments"));
    }

    @Test
    void ensureCatalogInUrl_keepsExistingDb() {
        // 现行为：强制切到任务表 catalog（多库同步）
        String url = "jdbc:mysql://127.0.0.1:3306/gcoll_aux?useSSL=false";
        assertEquals(url, DataxSyncService.ensureCatalogInUrl(url, "gcoll_aux", DatabaseType.MYSQL));
    }
}
