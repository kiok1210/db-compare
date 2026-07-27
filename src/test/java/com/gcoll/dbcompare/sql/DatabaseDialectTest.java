package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDialectTest {

    @Test
    void detectDatabaseType_mysql() {
        assertEquals(DatabaseType.MYSQL,
            DatabaseDialect.detectDatabaseType("jdbc:mysql://localhost:3306/db"));
    }

    @Test
    void detectDatabaseType_postgresql() {
        assertEquals(DatabaseType.POSTGRESQL,
            DatabaseDialect.detectDatabaseType("jdbc:postgresql://localhost:5432/db"));
    }

    @Test
    void detectDatabaseType_unknown_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> DatabaseDialect.detectDatabaseType("jdbc:unknown://host/db"));
    }

    @Test
    void buildPaginationSql_mysql() {
        String sql = DatabaseDialect.buildPaginationSql("users", "id", 100, 50, DatabaseType.MYSQL);
        assertTrue(sql.contains("LIMIT 50"));
        assertTrue(sql.contains("OFFSET 100"));
    }

    @Test
    void buildPaginationSql_mysql_largeOffset_keepsLong() {
        String sql = DatabaseDialect.buildPaginationSql("users", "id", 3_000_000_000L, 1000, DatabaseType.MYSQL);
        assertTrue(sql.contains("LIMIT 1000"));
        assertTrue(sql.contains("OFFSET 3000000000"));
    }

    @Test
    void buildPaginationSql_oracle() {
        String sql = DatabaseDialect.buildPaginationSql("users", "id", 100, 50, DatabaseType.ORACLE);
        assertTrue(sql.contains("FETCH NEXT 50 ROWS ONLY"));
    }

    @Test
    void escapeIdentifier_sqlserver() {
        assertEquals("[user_id]", DatabaseDialect.escapeIdentifier("user_id", DatabaseType.SQLSERVER));
    }
}
