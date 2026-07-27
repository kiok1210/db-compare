package com.gcoll.dbcompare.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseTypeTest {

    @Test
    void fromString_byEnumName() {
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromString("mysql"));
        assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromString("POSTGRESQL"));
    }

    @Test
    void fromString_byDisplayName() {
        assertEquals(DatabaseType.ORACLE, DatabaseType.fromString("Oracle"));
        assertEquals(DatabaseType.SQLSERVER, DatabaseType.fromString("SQL Server"));
    }

    @Test
    void fromString_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseType.fromString("unknown"));
    }

    @Test
    void fromString_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseType.fromString(""));
    }

    @Test
    void driverClass_mysql() {
        assertEquals("com.mysql.cj.jdbc.Driver", DatabaseType.MYSQL.getDriverClass());
    }
}
