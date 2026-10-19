package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailedTableComparatorH2Test {

    private Connection sourceConn;
    private Connection targetConn;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        sourceConn = DriverManager.getConnection(
                "jdbc:h2:mem:detail_src_" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection(
                "jdbc:h2:mem:detail_tgt_" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        String ddl = "CREATE TABLE t_order ("
                + "id BIGINT PRIMARY KEY, "
                + "user_id BIGINT, "
                + "status VARCHAR(20), "
                + "biz_date DATE, "
                + "amount DECIMAL(18,2)"
                + ")";
        for (Connection c : new Connection[]{sourceConn, targetConn}) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute(ddl);
                stmt.execute("CREATE INDEX idx_order_user ON t_order(user_id)");
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection c : new Connection[]{sourceConn, targetConn}) {
            if (c == null) {
                continue;
            }
            try (Statement stmt = c.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS t_order");
            }
            c.close();
        }
    }

    @Test
    void compareDetailed_detectsMismatchAndBuildsLocateSql() throws Exception {
        try (Statement s = sourceConn.createStatement(); Statement t = targetConn.createStatement()) {
            s.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(1,10,'PAID','2026-07-01',100.00)");
            s.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(2,11,'OPEN','2026-07-02',50.00)");
            t.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(1,10,'PAID','2026-07-01',100.00)");
            t.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(2,11,'CLOSED','2026-07-02',50.00)");
        }

        // H2+MODE=MySQL 与其它适配器单测一致，按 MYSQL 方言读元数据
        Map<String, Object> result = DetailedTableComparator.compareDetailed(
                sourceConn, targetConn,
                "t_order", null, null,
                "t_order", "t_order",
                DatabaseType.MYSQL, DatabaseType.MYSQL,
                100, 50);

        assertTrue(Boolean.TRUE.equals(result.get("detailed")));
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) result.get("fieldProfile");
        assertNotNull(profile);
        @SuppressWarnings("unchecked")
        List<?> pks = (List<?>) profile.get("primaryKeys");
        assertNotNull(pks);
        assertFalse(pks.isEmpty(), "应能读到主键");
        assertTrue(((Number) result.get("mismatchCount")).longValue() >= 1,
                "status 不一致应检出 mismatch，profile=" + profile);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) result.get("diffRows");
        assertFalse(diffs.isEmpty());
        Map<String, Object> mismatch = diffs.stream()
                .filter(r -> "mismatch".equals(r.get("type")))
                .findFirst()
                .orElse(null);
        assertNotNull(mismatch);
        assertNotNull(result.get("sourceSql"));
        assertNotNull(result.get("targetSql"));
        assertTrue(String.valueOf(result.get("sourceSql")).contains("SELECT"));
        assertTrue(String.valueOf(result.get("sourceSql")).contains("IN ("));
        // SELECT 应含主键 + 差异字段 status，而不应拖入无关关注列撑爆可读性
        String srcSql = String.valueOf(result.get("sourceSql"));
        assertTrue(srcSql.contains("status") || srcSql.toLowerCase().contains("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> locateSql = (Map<String, Object>) result.get("locateSql");
        assertNotNull(locateSql);
        @SuppressWarnings("unchecked")
        List<String> selectCols = (List<String>) locateSql.get("selectColumns");
        assertNotNull(selectCols);
        assertTrue(selectCols.stream().anyMatch(c -> "status".equalsIgnoreCase(c)));
        assertEquals(2L, ((Number) result.get("sourceCount")).longValue());
    }

    @Test
    void compareDetailed_onlyInSource_hasBatchedSql() throws Exception {
        try (Statement s = sourceConn.createStatement(); Statement t = targetConn.createStatement()) {
            s.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(1,10,'PAID','2026-07-01',100.00)");
            s.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(9,10,'OPEN','2026-07-01',1.00)");
            t.execute("INSERT INTO t_order(id,user_id,status,biz_date,amount) VALUES(1,10,'PAID','2026-07-01',100.00)");
        }

        Map<String, Object> result = DetailedTableComparator.compareDetailed(
                sourceConn, targetConn,
                "t_order", null, null,
                "t_order", "t_order",
                DatabaseType.MYSQL, DatabaseType.MYSQL,
                100, 50);

        assertEquals(1L, ((Number) result.get("onlyInSourceCount")).longValue());
        assertTrue(String.valueOf(result.get("sourceSql")).contains("SELECT"));
        assertTrue(String.valueOf(result.get("sourceSql")).contains("IN ("));
        assertTrue(String.valueOf(result.get("targetSql")).contains("SELECT"));
    }
}
