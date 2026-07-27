package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 in-memory integration test for DatabaseCompareAdapter:
 * - readRowByKeys
 * - compareTableCount
 * - compareTableData
 */
class DatabaseCompareAdapterH2Test {

    private Connection sourceConn;
    private Connection targetConn;

    @BeforeEach
    void setUp() throws Exception {
        sourceConn = DriverManager.getConnection(
            "jdbc:h2:mem:adapter_src;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        targetConn = DriverManager.getConnection(
            "jdbc:h2:mem:adapter_tgt;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");

        String ddl = "CREATE TABLE IF NOT EXISTS t_user (" +
            "id BIGINT PRIMARY KEY," +
            "name VARCHAR(100) NOT NULL," +
            "email VARCHAR(200)," +
            "score DECIMAL(10,2)" +
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
                stmt.execute("DROP TABLE IF EXISTS t_user");
            }
            c.close();
        }
    }

    @Test
    void compareTableCount_identicalData_zeroDiff() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "alice@test.com", 99.5);
        insertUser(sourceConn, 2L, "Bob", "bob@test.com", 88.0);
        insertUser(targetConn, 1L, "Alice", "alice@test.com", 99.5);
        insertUser(targetConn, 2L, "Bob", "bob@test.com", 88.0);

        long diff = DatabaseCompareAdapter.compareTableCount(
            sourceConn, targetConn, "t_user", DatabaseType.MYSQL);
        assertEquals(0, diff);
    }

    @Test
    void compareTableCount_missingRowInTarget_returnsPositiveDiff() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "alice@test.com", 99.5);
        insertUser(sourceConn, 2L, "Bob", "bob@test.com", 88.0);
        insertUser(targetConn, 1L, "Alice", "alice@test.com", 99.5);

        long diff = DatabaseCompareAdapter.compareTableCount(
            sourceConn, targetConn, "t_user", DatabaseType.MYSQL);
        assertEquals(1, diff);
    }

    @Test
    void readRowByKeys_existingRow_returnsData() throws Exception {
        insertUser(sourceConn, 42L, "Charlie", "charlie@test.com", 77.3);

        Map<String, Object> keyRow = new HashMap<>();
        keyRow.put("id", 42L);
        List<String> pk = Collections.singletonList("id");

        Map<String, Object> result = DatabaseCompareAdapter.readRowByKeys(
            sourceConn, "t_user", pk, keyRow, DatabaseType.MYSQL);

        assertNotNull(result);
        assertEquals("Charlie", result.get("NAME"));
    }

    @Test
    void readRowByKeys_missingRow_returnsNull() throws Exception {
        Map<String, Object> keyRow = new HashMap<>();
        keyRow.put("id", 9999L);

        Map<String, Object> result = DatabaseCompareAdapter.readRowByKeys(
            sourceConn, "t_user", Collections.singletonList("id"), keyRow, DatabaseType.MYSQL);

        assertNull(result);
    }

    @Test
    void compareRows_identicalRows_returnsTrue() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("id", 1L);
        r1.put("name", "Dave");

        Map<String, Object> r2 = new HashMap<>();
        r2.put("id", 1L);
        r2.put("name", "Dave");

        assertTrue(DatabaseCompareAdapter.compareRows(r1, r2));
    }

    @Test
    void compareRows_differentValue_returnsFalse() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("id", 1L);
        r1.put("name", "Dave");

        Map<String, Object> r2 = new HashMap<>();
        r2.put("id", 1L);
        r2.put("name", "Eve");

        assertFalse(DatabaseCompareAdapter.compareRows(r1, r2));
    }

    @Test
    void compareTableData_identicalData_returnsTrue() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(sourceConn, 2L, "Bob", "b@test.com", 80.0);
        insertUser(targetConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(targetConn, 2L, "Bob", "b@test.com", 80.0);

        // H2 ResultSet 列名多为大写，主键名需与元数据一致
        boolean result = DatabaseCompareAdapter.compareTableData(
            sourceConn, targetConn, "t_user", "t_user",
            Collections.singletonList("ID"),
            DatabaseType.MYSQL, DatabaseType.MYSQL, 100);

        assertTrue(result);
    }

    @Test
    void compareTableData_differentScore_returnsFalse() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(targetConn, 1L, "Alice", "a@test.com", 55.0); // different score

        boolean result = DatabaseCompareAdapter.compareTableData(
            sourceConn, targetConn, "t_user", "t_user",
            Collections.singletonList("ID"),
            DatabaseType.MYSQL, DatabaseType.MYSQL, 100);

        assertFalse(result);
    }

    @Test
    void compareTableData_emptyPrimaryKeys_returnsFalse() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(targetConn, 1L, "Alice", "a@test.com", 90.0);

        boolean result = DatabaseCompareAdapter.compareTableData(
            sourceConn, targetConn, "t_user", "t_user",
            Collections.emptyList(),
            DatabaseType.MYSQL, DatabaseType.MYSQL, 100);

        assertFalse(result, "Should return false when no primary keys provided");
    }

    @Test
    void findPrimaryKeyDiffDetails_missingAndExtra_returnsBothSides() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(sourceConn, 2L, "Bob", "b@test.com", 80.0);
        insertUser(sourceConn, 3L, "Carol", "c@test.com", 70.0);
        insertUser(targetConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(targetConn, 4L, "Dave", "d@test.com", 60.0);

        Map<String, Object> result = DatabaseCompareAdapter.findPrimaryKeyDiffDetails(
                sourceConn, targetConn, "t_user", "t_user",
                Collections.singletonList("ID"),
                DatabaseType.MYSQL, DatabaseType.MYSQL, 100, 50);

        assertEquals(3L, ((Number) result.get("sourceCount")).longValue());
        assertEquals(2L, ((Number) result.get("targetCount")).longValue());
        assertEquals(2L, ((Number) result.get("onlyInSourceCount")).longValue());
        assertEquals(1L, ((Number) result.get("onlyInTargetCount")).longValue());
        assertFalse(Boolean.TRUE.equals(result.get("consistent")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> onlySrc = (List<Map<String, Object>>) result.get("onlyInSource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> onlyTgt = (List<Map<String, Object>>) result.get("onlyInTarget");
        assertEquals(2, onlySrc.size());
        assertEquals(1, onlyTgt.size());
        assertEquals("only_in_target", onlyTgt.get(0).get("type"));
    }

    @Test
    void compareTableDataWithDetails_countMismatch_includesPkDiffRows() throws Exception {
        insertUser(sourceConn, 1L, "Alice", "a@test.com", 90.0);
        insertUser(sourceConn, 2L, "Bob", "b@test.com", 80.0);
        insertUser(targetConn, 1L, "Alice", "a@test.com", 90.0);

        Map<String, Object> result = DatabaseCompareAdapter.compareTableDataWithDetails(
                sourceConn, targetConn, "t_user", "t_user",
                Collections.singletonList("ID"),
                DatabaseType.MYSQL, DatabaseType.MYSQL, 100, 50);

        assertFalse(Boolean.TRUE.equals(result.get("consistent")));
        assertNotNull(result.get("reason"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffRows = (List<Map<String, Object>>) result.get("diffRows");
        assertNotNull(diffRows);
        assertFalse(diffRows.isEmpty());
        assertEquals("only_in_source", diffRows.get(0).get("type"));
    }

    // ---- helpers ----

    private void insertUser(Connection conn, long id, String name, String email, double score)
            throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                "INSERT INTO t_user(id, name, email, score) VALUES(%d, '%s', '%s', %f)",
                id, name, email, score));
        }
    }
}
