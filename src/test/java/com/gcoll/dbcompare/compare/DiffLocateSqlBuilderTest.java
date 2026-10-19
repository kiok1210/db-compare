package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffLocateSqlBuilderTest {

    @Test
    void buildSelectSql_withPkLiterals_mysqlStyle() {
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("id", 12);
        pk.put("code", "A'B");
        String sql = DiffLocateSqlBuilder.buildSelectSql(
                "`shop`.`orders`", Arrays.asList("id", "status", "amount"), pk, DatabaseType.MYSQL);
        assertTrue(sql.startsWith("SELECT "));
        assertTrue(sql.contains("FROM `shop`.`orders`"));
        assertTrue(sql.contains("WHERE "));
        assertTrue(sql.contains("= 12"));
        assertTrue(sql.contains("'A''B'"));
        assertTrue(sql.endsWith(";"));
    }

    @Test
    void buildSourceAndTargetSql_returnsBoth() {
        Map<String, Object> pk = Collections.singletonMap("id", 1L);
        Map<String, String> sqls = DiffLocateSqlBuilder.buildSourceAndTargetSql(
                "src.t", "tgt.t", Collections.singletonList("id"), pk,
                DatabaseType.MYSQL, DatabaseType.MYSQL);
        assertTrue(sqls.get("sourceSql").contains("FROM src.t"));
        assertTrue(sqls.get("targetSql").contains("FROM tgt.t"));
        assertEquals(sqls.get("sourceSql").contains("WHERE"), true);
    }

    @Test
    void buildSelectSql_nullPk_usesIsNull() {
        Map<String, Object> pk = Collections.singletonMap("id", null);
        String sql = DiffLocateSqlBuilder.buildSelectSql("t", null, pk, DatabaseType.H2);
        assertTrue(sql.contains("IS NULL"));
        assertTrue(sql.contains("SELECT *"));
    }

    @Test
    void buildSelectSqlInBatches_singlePk_usesIn() {
        List<Map<String, Object>> pks = new ArrayList<>();
        pks.add(Collections.singletonMap("customerId", "A1"));
        pks.add(Collections.singletonMap("customerId", "A2"));
        pks.add(Collections.singletonMap("customerId", "A3"));
        List<String> sqls = DiffLocateSqlBuilder.buildSelectSqlInBatches(
                "customers",
                Arrays.asList("customerId", "highestDPD"),
                Collections.singletonList("customerId"),
                pks,
                DatabaseType.MYSQL,
                200);
        assertEquals(1, sqls.size());
        String sql = sqls.get(0);
        assertTrue(sql.contains("SELECT `customerId`, `highestDPD` FROM customers"));
        assertTrue(sql.contains("`customerId` IN ("));
        assertTrue(sql.contains("'A1'"));
        assertTrue(sql.contains("'A2'"));
        assertTrue(sql.contains("'A3'"));
        assertFalse(sql.contains("`customerId` = "));
    }

    @Test
    void buildSelectSqlInBatches_segmentsWhenOverBatchSize() {
        List<Map<String, Object>> pks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pks.add(Collections.singletonMap("id", i));
        }
        List<String> sqls = DiffLocateSqlBuilder.buildSelectSqlInBatches(
                "t", Collections.singletonList("id"), Collections.singletonList("id"),
                pks, DatabaseType.H2, 2);
        assertEquals(3, sqls.size());
        assertTrue(sqls.get(0).contains("IN (0, 1)"));
        assertTrue(sqls.get(1).contains("IN (2, 3)"));
        assertTrue(sqls.get(2).contains("IN (4)"));
    }

    @Test
    void buildSelectSqlInBatches_compositePk_usesTupleIn() {
        List<Map<String, Object>> pks = new ArrayList<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("a", 1);
        r1.put("b", "x");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("a", 2);
        r2.put("b", "y");
        pks.add(r1);
        pks.add(r2);
        List<String> sqls = DiffLocateSqlBuilder.buildSelectSqlInBatches(
                "t", Arrays.asList("a", "b", "status"), Arrays.asList("a", "b"),
                pks, DatabaseType.MYSQL, 50);
        assertEquals(1, sqls.size());
        assertTrue(sqls.get(0).contains("(`a`, `b`) IN ((1, 'x'), (2, 'y'))"));
    }

    @Test
    void mergeSelectColumns_pkThenDiffFields() {
        List<String> cols = DiffLocateSqlBuilder.mergeSelectColumns(
                Arrays.asList("id"), Arrays.asList("status", "amount", "id"));
        assertEquals(Arrays.asList("id", "status", "amount"), cols);
    }

    @Test
    void buildBatchedLocateSql_joinsSourceAndTarget() {
        List<Map<String, Object>> pks = Collections.singletonList(
                Collections.singletonMap("id", 9));
        Map<String, Object> loc = DiffLocateSqlBuilder.buildBatchedLocateSql(
                "src.t", "tgt.t", Arrays.asList("id", "status"),
                Collections.singletonList("id"), pks,
                DatabaseType.MYSQL, DatabaseType.MYSQL, 100);
        assertTrue(String.valueOf(loc.get("sourceSql")).contains("IN (9)"));
        assertTrue(String.valueOf(loc.get("targetSql")).contains("FROM tgt.t"));
        assertEquals(1, ((List<?>) loc.get("sourceSqlBatches")).size());
    }
}
