package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class DataSourceScriptExporterTest {

    @TempDir
    Path tempDir;

    private DatasourceConfigRepository repository;
    private DataSourceScriptExporter exporter;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(DatasourceConfigRepository.class);
        exporter = new DataSourceScriptExporter(repository, tempDir.toString());
    }

    @Test
    void sqlStr_escapesQuotes() {
        assertTrue(DataSourceScriptExporter.sqlStr("a'b").contains("a''b"));
        assertTrue(DataSourceScriptExporter.sqlStr(null).equals("NULL"));
    }

    @Test
    void buildFullExportSql_containsMergeAndCipherPassword() {
        DataSourceConfig ds = sample(1L, "prod-mysql");
        String sql = exporter.buildFullExportSql(Collections.singletonList(ds));
        assertTrue(sql.contains("MERGE INTO dbcompare_datasource"));
        assertTrue(sql.contains("ENC:abc"));
        assertTrue(sql.contains("'MYSQL'"));
        assertTrue(sql.contains("prod-mysql"));
        assertTrue(sql.contains("\"schema\""));
    }

    @Test
    void buildDeleteSql_targetsId() {
        String sql = exporter.buildDeleteSql(9L, "gone");
        assertTrue(sql.contains("DELETE FROM dbcompare_datasource WHERE id = 9"));
    }

    @Test
    void recordChange_writesIncrementalAndFull() throws Exception {
        DataSourceConfig ds = sample(3L, "demo");
        when(repository.findAll()).thenReturn(Collections.singletonList(ds));

        exporter.recordChange(DataSourceScriptExporter.ChangeOp.INSERT, ds, null, null);

        Path full = tempDir.resolve("full").resolve("dbcompare_datasource_full.sql");
        assertTrue(Files.exists(full));
        String fullSql = new String(Files.readAllBytes(full), StandardCharsets.UTF_8);
        assertTrue(fullSql.contains("demo"));

        Path incDir = tempDir.resolve("incremental");
        assertTrue(Files.isDirectory(incDir));
        List<Path> files = Files.list(incDir).collect(java.util.stream.Collectors.toList());
        assertFalse(files.isEmpty());
        String inc = new String(Files.readAllBytes(files.get(0)), StandardCharsets.UTF_8);
        assertTrue(inc.contains("INSERT") || inc.contains("MERGE"));
        assertTrue(Files.exists(tempDir.resolve("CHANGELOG.md")));
    }

    @Test
    void recordChange_deleteWritesDeleteStatement() throws Exception {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        exporter.recordChange(DataSourceScriptExporter.ChangeOp.DELETE, null, 12L, "old");

        Path incDir = tempDir.resolve("incremental");
        Path file = Files.list(incDir).findFirst().orElseThrow(AssertionError::new);
        String sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(sql.contains("DELETE FROM dbcompare_datasource WHERE id = 12"));
    }

    private DataSourceConfig sample(Long id, String name) {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setId(id);
        ds.setName(name);
        ds.setDbType(DatabaseType.MYSQL);
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/db");
        ds.setUsername("u");
        ds.setPassword("ENC:abc");
        ds.setDriverClass("com.mysql.cj.jdbc.Driver");
        ds.setSchema("db");
        ds.setDescription("d's");
        ds.setEnabled(true);
        ds.setCreateTime(LocalDateTime.of(2026, 7, 15, 10, 0, 0));
        ds.setUpdateTime(LocalDateTime.of(2026, 7, 15, 11, 0, 0));
        return ds;
    }
}
