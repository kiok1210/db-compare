package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlHelperTest {

    @Test
    void quoteIdentifier_mysql() {
        assertEquals("`user_id`", SqlHelper.quoteIdentifier("user_id", DatabaseType.MYSQL));
    }

    @Test
    void quoteIdentifier_postgresql() {
        assertEquals("\"USER_ID\"", SqlHelper.quoteIdentifier("user_id", DatabaseType.POSTGRESQL));
    }

    @Test
    void buildWhereClause_singleKey() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 100L);

        SqlHelper.WhereClause clause = SqlHelper.buildWhereClause(
            Collections.singletonList("id"), row, DatabaseType.MYSQL);

        assertEquals("`id` = ?", clause.getSql());
        assertEquals(1, clause.getParams().size());
        assertEquals(100L, clause.getParams().get(0));
    }

    @Test
    void buildWhereClause_nullValue() {
        Map<String, Object> row = new HashMap<>();
        row.put("name", null);

        SqlHelper.WhereClause clause = SqlHelper.buildWhereClause(
            Collections.singletonList("name"), row, DatabaseType.MYSQL);

        assertEquals("`name` IS NULL", clause.getSql());
        assertTrue(clause.getParams().isEmpty());
    }

    @Test
    void buildWhereClause_compositeKey() {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", 1);
        row.put("user_id", "u001");

        SqlHelper.WhereClause clause = SqlHelper.buildWhereClause(
            Arrays.asList("tenant_id", "user_id"), row, DatabaseType.MYSQL);

        assertEquals("`tenant_id` = ? AND `user_id` = ?", clause.getSql());
        assertEquals(2, clause.getParams().size());
    }

}
