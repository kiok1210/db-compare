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
    void classify_detectsStatusAndBusinessDate() {
        List<DatabaseMetadataReader.ColumnMetadata> columns = Arrays.asList(
                column("order_status", "VARCHAR"),
                column("biz_date", "DATE"),
                column("total_amount", "DECIMAL"));

        FieldClassifier.FieldClassification result = FieldClassifier.classify(columns);

        assertTrue(result.getStatusFieldNames().stream().anyMatch(s -> s.equalsIgnoreCase("order_status")));
        assertTrue(result.getBusinessDateFieldNames().stream().anyMatch(s -> s.equalsIgnoreCase("biz_date")));
        assertTrue(result.isHasAmountField());
    }

    @Test
    void buildFocusColumns_ordersPkIndexStatusBizAmount() {
        FieldClassifier.FieldClassification c = FieldClassifier.classify(Arrays.asList(
                column("status", "VARCHAR"),
                column("trade_date", "DATE"),
                column("amount", "DECIMAL"),
                column("id", "BIGINT")));
        List<String> focus = FieldClassifier.buildFocusColumns(
                Collections.singletonList("id"),
                Collections.singletonList("user_id"),
                c);
        assertTrue(focus.get(0).equalsIgnoreCase("id"));
        assertTrue(focus.contains("user_id"));
        assertTrue(focus.stream().anyMatch(s -> s.equalsIgnoreCase("status")));
        assertTrue(focus.stream().anyMatch(s -> s.equalsIgnoreCase("trade_date")));
        assertTrue(focus.stream().anyMatch(s -> s.equalsIgnoreCase("amount")));
    }

    @Test
    void extractIndexColumnNames_skipsPrimaryKey() {
        DatabaseMetadataReader.IndexMetadata idx = new DatabaseMetadataReader.IndexMetadata();
        idx.setIndexName("idx_user");
        idx.setColumnName("user_id");
        DatabaseMetadataReader.IndexMetadata pkIdx = new DatabaseMetadataReader.IndexMetadata();
        pkIdx.setIndexName("PRIMARY");
        pkIdx.setColumnName("id");
        List<String> cols = FieldClassifier.extractIndexColumnNames(
                Arrays.asList(idx, pkIdx), Collections.singletonList("id"));
        assertTrue(cols.contains("user_id"));
        assertFalse(cols.stream().anyMatch(s -> s.equalsIgnoreCase("id")));
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
