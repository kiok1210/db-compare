package com.gcoll.dbcompare.dictionary;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldClassifierTest {

    @Test
    void classify_detectsAmountField() {
        List<DatabaseMetadataReader.ColumnMetadata> columns = Collections.singletonList(
            column("total_amount", "DECIMAL"));

        FieldClassifier.FieldClassification result = FieldClassifier.classify(columns);

        assertTrue(result.isHasAmountField());
        assertTrue(result.getAmountFieldNames().get(0).equalsIgnoreCase("total_amount"));
    }

    @Test
    void classify_excludesRateField() {
        List<DatabaseMetadataReader.ColumnMetadata> columns = Collections.singletonList(
            column("interest_rate", "DECIMAL"));

        FieldClassifier.FieldClassification result = FieldClassifier.classify(columns);

        assertFalse(result.isHasAmountField());
    }

    @Test
    void classify_detectsTimeField() {
        List<DatabaseMetadataReader.ColumnMetadata> columns = Collections.singletonList(
            column("update_time", "TIMESTAMP"));

        FieldClassifier.FieldClassification result = FieldClassifier.classify(columns);

        assertFalse(result.getTimeFieldNames().isEmpty());
        assertTrue(result.getTimeFieldNames().get(0).equalsIgnoreCase("update_time"));
    }

    @Test
    void buildSumSql_returnsNullWhenNoFields() {
        assertTrue(FieldClassifier.buildSumSql("t_order", null) == null);
        assertTrue(FieldClassifier.buildSumSql("t_order", Collections.emptyList()) == null);
    }

    @Test
    void buildSumSql_containsSumExpressions() {
        String sql = FieldClassifier.buildSumSql("t_order", Arrays.asList("amount", "fee"));

        assertTrue(sql.contains("SUM(amount)"));
        assertTrue(sql.contains("SUM(fee)"));
        assertTrue(sql.startsWith("SELECT "));
        assertTrue(sql.contains("FROM t_order"));
    }

    @Test
    void buildRowHashSql_mysqlDialect() {
        String sql = FieldClassifier.buildRowHashSql("t_user", Arrays.asList("id", "name"), "MYSQL");

        assertTrue(sql.contains("MD5("));
        assertTrue(sql.contains("CONCAT("));
        assertTrue(sql.contains("total_hash"));
    }

    @Test
    void buildRowHashSql_postgresqlDialect() {
        String sql = FieldClassifier.buildRowHashSql("t_user", Arrays.asList("id", "name"), "POSTGRESQL");

        assertTrue(sql.contains("HASHTEXT("));
        assertFalse(sql.contains("IFNULL"));
    }

    @Test
    void buildRowHashSql_oracleDialect() {
        String sql = FieldClassifier.buildRowHashSql("t_user", Arrays.asList("id", "name"), "ORACLE");

        assertTrue(sql.contains("ORA_HASH("));
    }

    private static DatabaseMetadataReader.ColumnMetadata column(String name, String type) {
        DatabaseMetadataReader.ColumnMetadata col = new DatabaseMetadataReader.ColumnMetadata();
        col.setColumnName(name);
        col.setDataType(type);
        col.setColumnSize(18);
        col.setNullable(true);
        return col;
    }

}
