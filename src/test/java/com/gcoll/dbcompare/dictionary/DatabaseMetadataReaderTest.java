package com.gcoll.dbcompare.dictionary;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMetadataReaderTest {

    @Test
    void buildSampleSql_mysql_containsLimit() {
        String sql = DatabaseMetadataReader.buildSampleSql("users", 0.1, DatabaseType.MYSQL, 50);
        assertTrue(sql.contains("RAND()"));
        assertTrue(sql.contains("LIMIT 50"));
    }

    @Test
    void buildSampleSql_postgresql_containsRandom() {
        String sql = DatabaseMetadataReader.buildSampleSql("users", 0.1, DatabaseType.POSTGRESQL, 30);
        assertTrue(sql.contains("RANDOM()"));
        assertTrue(sql.contains("LIMIT 30"));
    }

    @Test
    void buildSampleSql_sqlserver_containsTop() {
        String sql = DatabaseMetadataReader.buildSampleSql("users", 0.1, DatabaseType.SQLSERVER, 20);
        assertTrue(sql.contains("TOP 20"));
        assertTrue(sql.contains("NEWID()"));
    }
}
