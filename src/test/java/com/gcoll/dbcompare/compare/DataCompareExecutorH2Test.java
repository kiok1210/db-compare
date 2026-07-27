package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 in-memory integration test for DataCompareExecutor:
 * - compareSample
 * - compareIncremental
 */
class DataCompareExecutorH2Test {

    private Connection sourceConn;
    private Connection targetConn;

    @BeforeEach
    void setUp() throws Exception {
        sourceConn = DriverManager.getConnection(
            "jdbc:h2:mem:exec_src;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        targetConn = DriverManager.getConnection(
            "jdbc:h2:mem:exec_tgt;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");

        String ddl = "CREATE TABLE IF NOT EXISTS t_product (" +
            "id BIGINT PRIMARY KEY," +
            "name VARCHAR(100)," +
            "price DECIMAL(12,2)," +
            "update_time TIMESTAMP" +
            ")";
        for (Connection c : new Connection[]{sourceConn, targetConn}) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute(ddl);
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection c : new Connection[]{sourceConn, targetConn}) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS t_product");
            }
            c.close();
        }
    }

    // ========================= compareSample =========================

    @Test
    void compareSample_identicalData_isConsistent() throws Exception {
        insertProducts(sourceConn, 5);
        insertProducts(targetConn, 5);

        DatabaseMetadataReader.TableMetadata meta = buildTableMeta("t_product",
            Collections.singletonList("id"));

        Map<String, Object> result = DataCompareExecutor.compareSample(
            sourceConn, targetConn, "t_product", "t_product", meta,
            DatabaseType.MYSQL, DatabaseType.MYSQL, 1.0);

        assertTrue((Boolean) result.get("consistent"),
            "Identical data should be consistent in sample compare");

        @SuppressWarnings("unchecked")
        Map<String, Object> sample = (Map<String, Object>) result.get("sample");
        assertNotNull(sample);
        assertEquals(0L, sample.get("missing"));
        assertEquals(0L, sample.get("mismatched"));
    }

    @Test
    void compareSample_missingRowsInTarget_isInconsistent() throws Exception {
        insertProducts(sourceConn, 3);
        insertProducts(targetConn, 1);  // only 1 of 3

        DatabaseMetadataReader.TableMetadata meta = buildTableMeta("t_product",
            Collections.singletonList("id"));

        Map<String, Object> result = DataCompareExecutor.compareSample(
            sourceConn, targetConn, "t_product", "t_product", meta,
            DatabaseType.MYSQL, DatabaseType.MYSQL, 1.0);

        assertFalse((Boolean) result.get("consistent"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sample = (Map<String, Object>) result.get("sample");
        assertTrue((Long) sample.get("missing") > 0 || (Long) sample.get("mismatched") > 0);
    }

    @Test
    void compareSample_noPrimaryKey_skipped() throws Exception {
        insertProducts(sourceConn, 2);
        insertProducts(targetConn, 2);

        // Table metadata with empty primary keys
        DatabaseMetadataReader.TableMetadata meta = buildTableMeta("t_product",
            Collections.emptyList());

        Map<String, Object> result = DataCompareExecutor.compareSample(
            sourceConn, targetConn, "t_product", "t_product", meta,
            DatabaseType.MYSQL, DatabaseType.MYSQL, 0.5);

        assertTrue((Boolean) result.get("consistent"),
            "No PK → sample should be skipped and marked consistent");

        @SuppressWarnings("unchecked")
        Map<String, Object> sample = (Map<String, Object>) result.get("sample");
        assertTrue((Boolean) sample.get("skipped"), "Sample result should be skipped");
    }

    // ========================= compareIncremental =========================

    @Test
    void compareIncremental_identicalRecentData_isConsistent() throws Exception {
        // Use a fixed recent timestamp (within 24h window) for deterministic comparison
        String recentTs = new java.sql.Timestamp(System.currentTimeMillis() - 3600_000L).toString();
        insertProductsWithFixedTime(sourceConn, 3, recentTs);
        insertProductsWithFixedTime(targetConn, 3, recentTs);

        DatabaseMetadataReader.TableMetadata meta = buildTableMetaWithColumns(
            "t_product", Collections.singletonList("id"));

        Map<String, Object> result = DataCompareExecutor.compareIncremental(
            sourceConn, targetConn, "t_product", "t_product", meta,
            DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertTrue((Boolean) result.get("consistent"));
    }

    @Test
    void compareIncremental_noTimeField_skipped() throws Exception {
        // Table with no time columns
        String noTimeDdl = "CREATE TABLE IF NOT EXISTS t_static (" +
            "id BIGINT PRIMARY KEY, code VARCHAR(50))";
        try (Statement s = sourceConn.createStatement()) {
            s.execute(noTimeDdl);
        }
        try (Statement s = targetConn.createStatement()) {
            s.execute(noTimeDdl);
        }

        DatabaseMetadataReader.TableMetadata meta = buildTableMeta("t_static",
            Collections.singletonList("id"));
        // no time columns → FieldClassifier finds none

        Map<String, Object> result = DataCompareExecutor.compareIncremental(
            sourceConn, targetConn, "t_static", "t_static", meta,
            DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertTrue((Boolean) result.get("consistent"),
            "No time field → incremental skipped → should be consistent");

        @SuppressWarnings("unchecked")
        Map<String, Object> inc = (Map<String, Object>) result.get("incremental");
        assertTrue((Boolean) inc.get("skipped"));
    }

    // ---- helpers ----

    private void insertProducts(Connection conn, int count) throws Exception {
        for (int i = 1; i <= count; i++) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                    "INSERT INTO t_product(id, name, price, update_time) " +
                    "VALUES(%d, 'product_%d', %f, '2020-01-01 00:00:00')",
                    i, i, i * 10.0));
            }
        }
    }

    private void insertProductsWithFixedTime(Connection conn, int count, String ts)
            throws Exception {
        for (int i = 1; i <= count; i++) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                    "INSERT INTO t_product(id, name, price, update_time) " +
                    "VALUES(%d, 'product_%d', %f, '%s')",
                    i, i, i * 10.0, ts));
            }
        }
    }

    private DatabaseMetadataReader.TableMetadata buildTableMeta(
            String tableName, List<String> primaryKeys) {
        DatabaseMetadataReader.TableMetadata meta = new DatabaseMetadataReader.TableMetadata();
        meta.setTableName(tableName);
        meta.setDatabaseType(DatabaseType.MYSQL);
        meta.setPrimaryKeys(primaryKeys);
        meta.setColumns(new ArrayList<>());
        return meta;
    }

    private DatabaseMetadataReader.TableMetadata buildTableMetaWithColumns(
            String tableName, List<String> primaryKeys) {
        DatabaseMetadataReader.TableMetadata meta = buildTableMeta(tableName, primaryKeys);
        List<DatabaseMetadataReader.ColumnMetadata> columns = new ArrayList<>();
        columns.add(col("id", "BIGINT"));
        columns.add(col("name", "VARCHAR"));
        columns.add(col("price", "DECIMAL"));
        columns.add(col("update_time", "TIMESTAMP"));
        meta.setColumns(columns);
        return meta;
    }

    private DatabaseMetadataReader.ColumnMetadata col(String name, String type) {
        DatabaseMetadataReader.ColumnMetadata c = new DatabaseMetadataReader.ColumnMetadata();
        c.setColumnName(name);
        c.setDataType(type);
        c.setColumnSize(100);
        c.setNullable(true);
        return c;
    }
}
