package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerSqlScriptBuilderTest {

    @Test
    void quoteIdent_mysql_and_sqlserver() {
        assertEquals("`user`", ViewerSqlScriptBuilder.quoteIdent("user", DatabaseType.MYSQL));
        assertEquals("[user]", ViewerSqlScriptBuilder.quoteIdent("user", DatabaseType.SQLSERVER));
        assertEquals("\"user\"", ViewerSqlScriptBuilder.quoteIdent("user", DatabaseType.POSTGRESQL));
    }

    @Test
    void mapColumnType_varchar_to_oracle_and_mysql() {
        ViewerSqlScriptBuilder.ExportColumn col = new ViewerSqlScriptBuilder.ExportColumn(
                "name", Types.VARCHAR, "VARCHAR", 50, 0, true, false);
        assertEquals("VARCHAR(50)", ViewerSqlScriptBuilder.mapColumnType(col, DatabaseType.MYSQL));
        assertEquals("VARCHAR2(50)", ViewerSqlScriptBuilder.mapColumnType(col, DatabaseType.ORACLE));
        assertEquals("NVARCHAR(50)", ViewerSqlScriptBuilder.mapColumnType(col, DatabaseType.SQLSERVER));
        assertEquals("TEXT", ViewerSqlScriptBuilder.mapColumnType(col, DatabaseType.SQLITE));
    }

    @Test
    void formatLiteral_null_string_number() {
        assertEquals("NULL", ViewerSqlScriptBuilder.formatLiteral(null, Types.VARCHAR, DatabaseType.MYSQL));
        assertEquals("'O''Brien'", ViewerSqlScriptBuilder.formatLiteral("O'Brien", Types.VARCHAR, DatabaseType.H2));
        assertEquals("12.50", ViewerSqlScriptBuilder.formatLiteral(new BigDecimal("12.50"), Types.DECIMAL, DatabaseType.MYSQL));
        assertEquals("TRUE", ViewerSqlScriptBuilder.formatLiteral(Boolean.TRUE, Types.BOOLEAN, DatabaseType.POSTGRESQL));
        assertEquals("1", ViewerSqlScriptBuilder.formatLiteral(Boolean.TRUE, Types.BOOLEAN, DatabaseType.MYSQL));
    }

    @Test
    void buildCreateTable_includesPk_and_mysql_quotes() {
        List<ViewerSqlScriptBuilder.ExportColumn> cols = Arrays.asList(
                new ViewerSqlScriptBuilder.ExportColumn("id", Types.BIGINT, "BIGINT", 19, 0, false, true),
                new ViewerSqlScriptBuilder.ExportColumn("name", Types.VARCHAR, "VARCHAR", 32, 0, true, false)
        );
        String ddl = ViewerSqlScriptBuilder.buildCreateTable("`t_user`", cols,
                Collections.singletonList("id"), DatabaseType.MYSQL);
        assertTrue(ddl.contains("CREATE TABLE `t_user`"));
        assertTrue(ddl.contains("`id` BIGINT"));
        assertTrue(ddl.contains("AUTO_INCREMENT"));
        assertTrue(ddl.contains("PRIMARY KEY (`id`)"));
        assertFalse(ddl.contains(",\n);"));
    }

    @Test
    void buildInsert_and_drop() {
        List<ViewerSqlScriptBuilder.ExportColumn> cols = Collections.singletonList(
                new ViewerSqlScriptBuilder.ExportColumn("name", Types.VARCHAR, "VARCHAR", 10, 0, true, false));
        String ins = ViewerSqlScriptBuilder.buildInsert("`t`", cols,
                Collections.singletonList("a"), DatabaseType.MYSQL);
        assertEquals("INSERT INTO `t` (`name`) VALUES ('a');\n", ins);
        assertTrue(ViewerSqlScriptBuilder.buildDropTable("`t`", DatabaseType.MYSQL)
                .startsWith("DROP TABLE IF EXISTS"));
        assertTrue(ViewerSqlScriptBuilder.buildDropTable("\"T\"", DatabaseType.ORACLE)
                .contains("EXECUTE IMMEDIATE"));
    }

    @Test
    void clampMaxRows_bounds() {
        assertEquals(ViewerSqlScriptBuilder.DEFAULT_MAX_ROWS, ViewerSqlScriptBuilder.clampMaxRows(null));
        assertEquals(1, ViewerSqlScriptBuilder.clampMaxRows(1));
        assertEquals(ViewerSqlScriptBuilder.HARD_MAX_ROWS, ViewerSqlScriptBuilder.clampMaxRows(999999));
    }

    @Test
    void qualifyTable_mysql_uses_catalog() {
        String q = ViewerSqlScriptBuilder.qualifyTable("shop", null, "goods", DatabaseType.MYSQL);
        assertEquals("`shop`.`goods`", q);
    }
}
