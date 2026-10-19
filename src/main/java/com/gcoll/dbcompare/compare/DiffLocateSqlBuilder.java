package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.sql.SqlHelper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 生成可在源/目标库直接执行的定位 SQL（字面量 WHERE，便于可视化排查差异行）。
 * <p>
 * 多行差异优先使用主键 {@code IN}（超大列表分段）；单行仍可用等值条件。
 */
public final class DiffLocateSqlBuilder {

    /** 单段 IN 默认上限，避免 SQL 过长 / 驱动包体限制 */
    public static final int DEFAULT_IN_BATCH_SIZE = 200;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DiffLocateSqlBuilder() {
    }

    /**
     * 构建 SELECT 定位 SQL（单行主键等值）。
     *
     * @param qualifiedTable 已带 schema 的表名（可含引号）
     * @param selectColumns  SELECT 列；为空则 SELECT *
     * @param pkValues       主键列→值
     * @param dbType         方言（标识符引号与字面量）
     */
    public static String buildSelectSql(String qualifiedTable,
                                        List<String> selectColumns,
                                        Map<String, Object> pkValues,
                                        DatabaseType dbType) {
        if (qualifiedTable == null || qualifiedTable.trim().isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder(buildSelectFrom(qualifiedTable, selectColumns, dbType));
        String where = buildWhereLiterals(pkValues, dbType);
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(';');
        return sql.toString();
    }

    /**
     * 按主键列表生成分段 IN 定位 SQL（单列 {@code col IN (...)}，复合主键 {@code (c1,c2) IN ((...),...)}）。
     *
     * @param batchSize 每段最多多少组主键；&lt;=0 时用 {@link #DEFAULT_IN_BATCH_SIZE}
     */
    public static List<String> buildSelectSqlInBatches(String qualifiedTable,
                                                       List<String> selectColumns,
                                                       List<String> primaryKeys,
                                                       List<Map<String, Object>> pkRows,
                                                       DatabaseType dbType,
                                                       int batchSize) {
        if (qualifiedTable == null || qualifiedTable.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> pkCols = normalizePkColumns(primaryKeys, pkRows);
        List<Map<String, Object>> rows = pkRows == null ? Collections.<Map<String, Object>>emptyList() : pkRows;
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            if (pkCols.isEmpty()) {
                continue;
            }
            // 任一首列主键为 null 时不进 IN（用不上字面量 IN）
            boolean hasNullPk = false;
            for (String col : pkCols) {
                if (!row.containsKey(col) || row.get(col) == null) {
                    hasNullPk = true;
                    break;
                }
            }
            if (!hasNullPk) {
                usable.add(row);
            }
        }
        if (usable.isEmpty()) {
            return Collections.emptyList();
        }
        int size = batchSize > 0 ? batchSize : DEFAULT_IN_BATCH_SIZE;
        List<String> batches = new ArrayList<>();
        for (int from = 0; from < usable.size(); from += size) {
            int to = Math.min(from + size, usable.size());
            List<Map<String, Object>> chunk = usable.subList(from, to);
            String where = buildInWhere(pkCols, chunk, dbType);
            if (where.isEmpty()) {
                continue;
            }
            batches.add(buildSelectFrom(qualifiedTable, selectColumns, dbType) + " WHERE " + where + ';');
        }
        return batches;
    }

    /**
     * 源/目标各生成分段 IN SQL，并附带合并文本（多段用空行分隔，便于一次复制）。
     */
    public static Map<String, Object> buildBatchedLocateSql(String sourceTable,
                                                            String targetTable,
                                                            List<String> selectColumns,
                                                            List<String> primaryKeys,
                                                            List<Map<String, Object>> pkRows,
                                                            DatabaseType sourceDbType,
                                                            DatabaseType targetDbType,
                                                            int batchSize) {
        List<String> sourceBatches = buildSelectSqlInBatches(
                sourceTable, selectColumns, primaryKeys, pkRows, sourceDbType, batchSize);
        List<String> targetBatches = buildSelectSqlInBatches(
                targetTable, selectColumns, primaryKeys, pkRows, targetDbType, batchSize);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceSqlBatches", sourceBatches);
        out.put("targetSqlBatches", targetBatches);
        out.put("sourceSql", joinBatches(sourceBatches));
        out.put("targetSql", joinBatches(targetBatches));
        out.put("batchSize", batchSize > 0 ? batchSize : DEFAULT_IN_BATCH_SIZE);
        out.put("pkCount", pkRows == null ? 0 : pkRows.size());
        return out;
    }

    public static Map<String, String> buildSourceAndTargetSql(String sourceTable,
                                                              String targetTable,
                                                              List<String> selectColumns,
                                                              Map<String, Object> pkValues,
                                                              DatabaseType sourceDbType,
                                                              DatabaseType targetDbType) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("sourceSql", buildSelectSql(sourceTable, selectColumns, pkValues, sourceDbType));
        out.put("targetSql", buildSelectSql(targetTable, selectColumns, pkValues, targetDbType));
        return out;
    }

    static String joinBatches(List<String> batches) {
        if (batches == null || batches.isEmpty()) {
            return "";
        }
        return String.join("\n\n", batches);
    }

    static String buildSelectFrom(String qualifiedTable, List<String> selectColumns, DatabaseType dbType) {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> cols = selectColumns == null ? Collections.<String>emptyList() : selectColumns;
        if (cols.isEmpty()) {
            sql.append("*");
        } else {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(SqlHelper.quoteIdentifier(cols.get(i), dbType));
            }
        }
        sql.append(" FROM ").append(qualifiedTable.trim());
        return sql.toString();
    }

    static List<String> normalizePkColumns(List<String> primaryKeys, List<Map<String, Object>> pkRows) {
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            List<String> cols = new ArrayList<>();
            for (String c : primaryKeys) {
                if (c != null && !c.isEmpty()) {
                    cols.add(c);
                }
            }
            if (!cols.isEmpty()) {
                return cols;
            }
        }
        if (pkRows != null) {
            for (Map<String, Object> row : pkRows) {
                if (row != null && !row.isEmpty()) {
                    return new ArrayList<>(row.keySet());
                }
            }
        }
        return Collections.emptyList();
    }

    static String buildInWhere(List<String> pkCols, List<Map<String, Object>> chunk, DatabaseType dbType) {
        if (pkCols == null || pkCols.isEmpty() || chunk == null || chunk.isEmpty()) {
            return "";
        }
        if (pkCols.size() == 1) {
            String col = SqlHelper.quoteIdentifier(pkCols.get(0), dbType);
            List<String> literals = new ArrayList<>(chunk.size());
            for (Map<String, Object> row : chunk) {
                literals.add(literal(row.get(pkCols.get(0)), dbType));
            }
            return col + " IN (" + String.join(", ", literals) + ")";
        }
        // 复合主键：行构造 (c1, c2) IN ((v1, v2), ...)
        StringBuilder cols = new StringBuilder("(");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                cols.append(", ");
            }
            cols.append(SqlHelper.quoteIdentifier(pkCols.get(i), dbType));
        }
        cols.append(')');
        List<String> tuples = new ArrayList<>(chunk.size());
        for (Map<String, Object> row : chunk) {
            StringBuilder t = new StringBuilder("(");
            for (int i = 0; i < pkCols.size(); i++) {
                if (i > 0) {
                    t.append(", ");
                }
                t.append(literal(row.get(pkCols.get(i)), dbType));
            }
            t.append(')');
            tuples.add(t.toString());
        }
        return cols + " IN (" + String.join(", ", tuples) + ")";
    }

    static String buildWhereLiterals(Map<String, Object> pkValues, DatabaseType dbType) {
        if (pkValues == null || pkValues.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : pkValues.entrySet()) {
            String col = e.getKey();
            if (col == null || col.isEmpty()) {
                continue;
            }
            Object val = e.getValue();
            String qCol = SqlHelper.quoteIdentifier(col, dbType);
            if (val == null) {
                parts.add(qCol + " IS NULL");
            } else {
                parts.add(qCol + " = " + literal(val, dbType));
            }
        }
        return String.join(" AND ", parts);
    }

    static String literal(Object value, DatabaseType dbType) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).toPlainString();
            }
            return value.toString();
        }
        if (value instanceof Boolean) {
            if (dbType == DatabaseType.POSTGRESQL) {
                return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
            }
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }
        if (value instanceof java.sql.Date) {
            return "'" + ((java.sql.Date) value).toLocalDate().format(DATE_FMT) + "'";
        }
        if (value instanceof Timestamp) {
            return "'" + ((Timestamp) value).toLocalDateTime().format(TS_FMT) + "'";
        }
        if (value instanceof java.util.Date) {
            return "'" + new Timestamp(((java.util.Date) value).getTime()).toLocalDateTime().format(TS_FMT) + "'";
        }
        if (value instanceof LocalDate) {
            return "'" + ((LocalDate) value).format(DATE_FMT) + "'";
        }
        if (value instanceof LocalDateTime) {
            return "'" + ((LocalDateTime) value).format(TS_FMT) + "'";
        }
        String s = String.valueOf(value);
        // 转义单引号；避免换行破坏可视化 SQL
        s = s.replace("'", "''").replace("\r", " ").replace("\n", " ");
        return "'" + s + "'";
    }

    /** 合并 SELECT 列：先 first 再 second（去重保序）。常用于 主键 + 差异字段。 */
    public static List<String> mergeSelectColumns(List<String> focusColumns, List<String> primaryKeys) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        if (focusColumns != null) {
            for (String c : focusColumns) {
                if (c != null && !c.isEmpty()) {
                    cols.add(c);
                }
            }
        }
        if (primaryKeys != null) {
            for (String c : primaryKeys) {
                if (c != null && !c.isEmpty()) {
                    cols.add(c);
                }
            }
        }
        return new ArrayList<>(cols);
    }
}
