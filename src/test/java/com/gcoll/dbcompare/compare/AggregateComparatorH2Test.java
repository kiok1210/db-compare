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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 in-memory integration test for AggregateComparator.
 * Tests COUNT and SUM dimensions only (H2 does not support MD5 for row-hash).
 */
class AggregateComparatorH2Test {

    private Connection sourceConn;
    private Connection targetConn;

    private static final String JDBC_URL =
        "jdbc:h2:mem:agg_test_%s;DB_CLOSE_DELAY=-1;MODE=MySQL";

    @BeforeEach
    void setUp() throws Exception {
        sourceConn = DriverManager.getConnection(
            String.format(JDBC_URL, "source"), "sa", "");
        targetConn = DriverManager.getConnection(
            String.format(JDBC_URL, "target"), "sa", "");

        for (Connection conn : new Connection[]{sourceConn, targetConn}) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS t_order (" +
                    "id BIGINT PRIMARY KEY," +
                    "order_amount DECIMAL(18,2)," +
                    "fee_amount DECIMAL(18,2)," +
                    "status VARCHAR(20)" +
                    ")");
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection conn : new Connection[]{sourceConn, targetConn}) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS t_order");
            }
            conn.close();
        }
    }

    /**
     * When both source and target have identical data, count and sum should be consistent.
     */
    @Test
    void compare_identicalData_countAndSumConsistent() throws Exception {
        insertOrders(sourceConn, new long[]{1, 2, 3},
            new double[]{100.0, 200.0, 300.0}, new double[]{10.0, 20.0, 30.0});
        insertOrders(targetConn, new long[]{1, 2, 3},
            new double[]{100.0, 200.0, 300.0}, new double[]{10.0, 20.0, 30.0});

        List<DatabaseMetadataReader.ColumnMetadata> columns = buildColumns();

        AggregateComparator.AggregateCompareResult result =
            AggregateComparator.compare(sourceConn, targetConn, "t_order", "t_order", columns,
                DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertEquals(3, result.getSourceCount());
        assertEquals(3, result.getTargetCount());
        assertTrue(result.isCountConsistent(), "Row count should be consistent");
        assertTrue(result.isAmountSumConsistent(), "Amount sum should be consistent");
        assertEquals(0, result.getCountDiff());
    }

    /**
     * When target has fewer rows, count dimension should detect inconsistency.
     */
    @Test
    void compare_countMismatch_detectedAsInconsistent() throws Exception {
        insertOrders(sourceConn, new long[]{1, 2, 3},
            new double[]{100.0, 200.0, 300.0}, new double[]{10.0, 20.0, 30.0});
        insertOrders(targetConn, new long[]{1, 2},
            new double[]{100.0, 200.0}, new double[]{10.0, 20.0});

        List<DatabaseMetadataReader.ColumnMetadata> columns = buildColumns();

        AggregateComparator.AggregateCompareResult result =
            AggregateComparator.compare(sourceConn, targetConn, "t_order", "t_order", columns,
                DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertFalse(result.isCountConsistent(), "Count mismatch should be detected");
        assertEquals(3, result.getSourceCount());
        assertEquals(2, result.getTargetCount());
        assertEquals(1, result.getCountDiff());
        assertFalse(result.isConsistent());
    }

    /**
     * When target has same row count but different amount sum, sum dimension detects it.
     */
    @Test
    void compare_amountSumMismatch_detectedAsInconsistent() throws Exception {
        insertOrders(sourceConn, new long[]{1, 2},
            new double[]{100.0, 200.0}, new double[]{10.0, 20.0});
        // Target row 2 has different amount
        insertOrders(targetConn, new long[]{1, 2},
            new double[]{100.0, 999.0}, new double[]{10.0, 20.0});

        List<DatabaseMetadataReader.ColumnMetadata> columns = buildColumns();

        AggregateComparator.AggregateCompareResult result =
            AggregateComparator.compare(sourceConn, targetConn, "t_order", "t_order", columns,
                DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertTrue(result.isCountConsistent(), "Row count should still match");
        assertFalse(result.isAmountSumConsistent(), "Amount sum mismatch should be detected");
        assertFalse(result.getAmountDiffFields().isEmpty());
        assertFalse(result.isConsistent());
    }

    /**
     * Empty tables should be consistent with each other.
     */
    @Test
    void compare_emptyTables_areConsistent() throws Exception {
        List<DatabaseMetadataReader.ColumnMetadata> columns = buildColumns();

        AggregateComparator.AggregateCompareResult result =
            AggregateComparator.compare(sourceConn, targetConn, "t_order", "t_order", columns,
                DatabaseType.MYSQL, DatabaseType.MYSQL);

        assertEquals(0, result.getSourceCount());
        assertEquals(0, result.getTargetCount());
        assertTrue(result.isCountConsistent());
        assertTrue(result.isAmountSumConsistent());
    }

    /**
     * getDiffSummary should contain meaningful content when inconsistent.
     */
    @Test
    void compare_diffSummary_containsDetails() throws Exception {
        insertOrders(sourceConn, new long[]{1, 2, 3},
            new double[]{500.0, 600.0, 700.0}, new double[]{5.0, 6.0, 7.0});
        insertOrders(targetConn, new long[]{1},
            new double[]{500.0}, new double[]{5.0});

        List<DatabaseMetadataReader.ColumnMetadata> columns = buildColumns();

        AggregateComparator.AggregateCompareResult result =
            AggregateComparator.compare(sourceConn, targetConn, "t_order", "t_order", columns,
                DatabaseType.MYSQL, DatabaseType.MYSQL);

        String summary = result.getDiffSummary();
        assertNotEquals("一致", summary);
        assertTrue(summary.contains("条数不一致"));
    }

    // ---- helpers ----

    private void insertOrders(Connection conn, long[] ids, double[] amounts, double[] fees)
            throws Exception {
        for (int i = 0; i < ids.length; i++) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format(
                    "INSERT INTO t_order(id, order_amount, fee_amount, status) VALUES(%d, %f, %f, 'PAID')",
                    ids[i], amounts[i], fees[i]));
            }
        }
    }

    private List<DatabaseMetadataReader.ColumnMetadata> buildColumns() {
        List<DatabaseMetadataReader.ColumnMetadata> columns = new ArrayList<>();
        columns.add(col("id", "BIGINT"));
        columns.add(col("order_amount", "DECIMAL"));
        columns.add(col("fee_amount", "DECIMAL"));
        columns.add(col("status", "VARCHAR"));
        return columns;
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
