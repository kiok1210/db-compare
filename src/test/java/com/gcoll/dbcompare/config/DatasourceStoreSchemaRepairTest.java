package com.gcoll.dbcompare.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceStoreSchemaRepairTest {

    @Test
    void repair_renamesCamelCaseColumnsToSnakeCase() throws Exception {
        String url = "jdbc:h2:mem:ds_repair_test;DB_CLOSE_DELAY=-1;MODE=MySQL";
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE dbcompare_datasource ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(100), dbType VARCHAR(32),"
                    + "url VARCHAR(500), username VARCHAR(100), password VARCHAR(200),"
                    + "driverClass VARCHAR(200), schema VARCHAR(100), description VARCHAR(500),"
                    + "enabled BOOLEAN, createTime TIMESTAMP, updateTime TIMESTAMP)");
            stmt.execute("INSERT INTO dbcompare_datasource (id, name, dbType, url, username, password, "
                    + "driverClass, enabled, createTime, updateTime) VALUES "
                    + "(1, 't', 'MYSQL', 'jdbc:h2:mem:x', 'sa', '', 'org.h2.Driver', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setDriverClassName("org.h2.Driver");
        DatasourceStoreSchemaRepair.repair(ds);

        Set<String> cols = new HashSet<>();
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             ResultSet rs = conn.getMetaData().getColumns(null, null, "DBCOMPARE_DATASOURCE", null)) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        assertTrue(cols.contains("DB_TYPE"), "cols=" + cols);
        assertTrue(cols.contains("DRIVER_CLASS"), "cols=" + cols);
        assertTrue(cols.contains("CREATE_TIME"), "cols=" + cols);
        assertTrue(cols.contains("UPDATE_TIME"), "cols=" + cols);
    }
}
