package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将 JDBC 元数据/行数据渲染为指定目标库方言的 DDL / DML。
 */
public final class ViewerSqlScriptBuilder {

    public static final int DEFAULT_MAX_ROWS = 20_000;
    public static final int HARD_MAX_ROWS = 100_000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ViewerSqlScriptBuilder() {
    }

    public static String quoteIdent(String identifier, DatabaseType dbType) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER:
                return "[" + identifier.replace("]", "]]") + "]";
            default:
                return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }
    }

    public static String qualifyTable(String catalog, String schema, String table, DatabaseType dbType) {
        StringBuilder sb = new StringBuilder();
        if (catalog != null && !catalog.trim().isEmpty()
                && (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB
                || dbType == DatabaseType.SQLSERVER)) {
            sb.append(quoteIdent(catalog.trim(), dbType)).append('.');
        }
        if (schema != null && !schema.trim().isEmpty()
                && dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB) {
            sb.append(quoteIdent(schema.trim(), dbType)).append('.');
        }
        sb.append(quoteIdent(table, dbType));
        return sb.toString();
    }

    public static String mapColumnType(ExportColumn col, DatabaseType target) {
        int jdbc = col.jdbcType;
        int size = Math.max(0, col.size);
        int scale = Math.max(0, col.decimalDigits);
        String typeName = col.typeName == null ? "" : col.typeName.toUpperCase(Locale.ROOT);

        if (isBoolean(jdbc, typeName)) {
            switch (target) {
                case ORACLE:
                    return "NUMBER(1)";
                case SQLSERVER:
                    return "BIT";
                case SQLITE:
                    return "INTEGER";
                case MYSQL:
                case GOLDENDB:
                    return "TINYINT(1)";
                default:
                    return "BOOLEAN";
            }
        }
        if (jdbc == Types.BIGINT || "BIGINT".equals(typeName) || "INT8".equals(typeName)) {
            return target == DatabaseType.ORACLE ? "NUMBER(19)" : "BIGINT";
        }
        if (jdbc == Types.INTEGER || jdbc == Types.SMALLINT || jdbc == Types.TINYINT
                || typeName.contains("INT") && !typeName.contains("INTERVAL") && !typeName.contains("POINT")) {
            if (target == DatabaseType.ORACLE) {
                return "NUMBER(10)";
            }
            if (target == DatabaseType.SQLITE) {
                return "INTEGER";
            }
            return jdbc == Types.SMALLINT ? "SMALLINT" : "INT";
        }
        if (jdbc == Types.DECIMAL || jdbc == Types.NUMERIC || typeName.contains("NUMBER")
                || typeName.contains("DECIMAL") || typeName.contains("NUMERIC")) {
            int p = size > 0 ? Math.min(size, 38) : 18;
            int s = Math.min(scale, p);
            switch (target) {
                case ORACLE:
                    return "NUMBER(" + p + "," + s + ")";
                case SQLITE:
                    return "NUMERIC";
                default:
                    return "DECIMAL(" + p + "," + s + ")";
            }
        }
        if (jdbc == Types.FLOAT || jdbc == Types.REAL || jdbc == Types.DOUBLE
                || typeName.contains("FLOAT") || typeName.contains("DOUBLE")) {
            switch (target) {
                case ORACLE:
                    return "BINARY_DOUBLE";
                case SQLITE:
                    return "REAL";
                case POSTGRESQL:
                    return "DOUBLE PRECISION";
                default:
                    return "DOUBLE";
            }
        }
        if (jdbc == Types.DATE || "DATE".equals(typeName)) {
            return target == DatabaseType.SQLITE ? "TEXT" : "DATE";
        }
        if (jdbc == Types.TIME || jdbc == Types.TIME_WITH_TIMEZONE) {
            return target == DatabaseType.ORACLE ? "VARCHAR2(20)" : (target == DatabaseType.SQLITE ? "TEXT" : "TIME");
        }
        if (jdbc == Types.TIMESTAMP || jdbc == Types.TIMESTAMP_WITH_TIMEZONE
                || typeName.contains("TIMESTAMP") || typeName.contains("DATETIME")) {
            switch (target) {
                case MYSQL:
                case GOLDENDB:
                    return "DATETIME";
                case SQLSERVER:
                    return "DATETIME2";
                case SQLITE:
                    return "TEXT";
                default:
                    return "TIMESTAMP";
            }
        }
        if (jdbc == Types.BLOB || jdbc == Types.BINARY || jdbc == Types.VARBINARY || jdbc == Types.LONGVARBINARY
                || typeName.contains("BLOB") || typeName.contains("BYTEA") || typeName.contains("BINARY")) {
            switch (target) {
                case POSTGRESQL:
                    return "BYTEA";
                case SQLSERVER:
                    return "VARBINARY(MAX)";
                default:
                    return "BLOB";
            }
        }
        if (jdbc == Types.CLOB || jdbc == Types.LONGVARCHAR || jdbc == Types.LONGNVARCHAR
                || typeName.contains("CLOB") || typeName.contains("TEXT") || size <= 0 || size > 4000) {
            switch (target) {
                case MYSQL:
                case GOLDENDB:
                    return "LONGTEXT";
                case POSTGRESQL:
                case SQLITE:
                    return "TEXT";
                case SQLSERVER:
                    return "NVARCHAR(MAX)";
                case ORACLE:
                    return "CLOB";
                default:
                    return "CLOB";
            }
        }
        int varcharLen = size > 0 ? Math.min(size, target == DatabaseType.ORACLE ? 4000 : 8000) : 255;
        switch (target) {
            case ORACLE:
                return "VARCHAR2(" + varcharLen + ")";
            case SQLSERVER:
                return "NVARCHAR(" + varcharLen + ")";
            case SQLITE:
                return "TEXT";
            default:
                return "VARCHAR(" + varcharLen + ")";
        }
    }

    public static String formatLiteral(Object value, int jdbcType, DatabaseType target) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return formatBytes((byte[]) value, target);
        }
        if (value instanceof Boolean) {
            return formatBoolean((Boolean) value, target);
        }
        if (value instanceof Number && !(value instanceof BigDecimal) && isNumericJdbc(jdbcType)) {
            return value instanceof Double || value instanceof Float
                    ? new BigDecimal(value.toString()).toPlainString()
                    : String.valueOf(value);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof java.sql.Date) {
            return formatDate(((java.sql.Date) value).toLocalDate(), target);
        }
        if (value instanceof java.sql.Timestamp) {
            return formatTimestamp(((java.sql.Timestamp) value).toLocalDateTime(), target);
        }
        if (value instanceof java.util.Date) {
            java.sql.Timestamp ts = new java.sql.Timestamp(((java.util.Date) value).getTime());
            return formatTimestamp(ts.toLocalDateTime(), target);
        }
        if (value instanceof LocalDate) {
            return formatDate((LocalDate) value, target);
        }
        if (value instanceof LocalDateTime) {
            return formatTimestamp((LocalDateTime) value, target);
        }
        if (isBoolean(jdbcType, "") && isBooleanText(String.valueOf(value))) {
            return formatBoolean(Boolean.parseBoolean(String.valueOf(value))
                    || "1".equals(String.valueOf(value)), target);
        }
        if (isNumericJdbc(jdbcType) && isPlainNumber(String.valueOf(value))) {
            return String.valueOf(value);
        }
        return quoteString(String.valueOf(value), target);
    }

    public static String buildDropTable(String qualifiedTable, DatabaseType target) {
        if (target == DatabaseType.ORACLE) {
            return "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + qualifiedTable.replace("'", "''")
                    + "'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;\n/\n";
        }
        return "DROP TABLE IF EXISTS " + qualifiedTable + ";\n";
    }

    public static String buildCreateTable(String qualifiedTable, List<ExportColumn> columns,
                                          List<String> primaryKeys, DatabaseType target) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualifiedTable).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ExportColumn col = columns.get(i);
            sb.append("  ").append(quoteIdent(col.name, target)).append(' ')
                    .append(mapColumnType(col, target));
            if (col.autoIncrement && supportsIdentity(target) && primaryKeys.size() == 1
                    && primaryKeys.get(0).equalsIgnoreCase(col.name)) {
                sb.append(' ').append(identityClause(target));
            }
            sb.append(col.nullable ? "" : " NOT NULL");
            if (i < columns.size() - 1 || (primaryKeys != null && !primaryKeys.isEmpty())) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            sb.append("  PRIMARY KEY (")
                    .append(primaryKeys.stream()
                            .map(pk -> quoteIdent(pk, target))
                            .collect(Collectors.joining(", ")))
                    .append(")\n");
        }
        sb.append(");\n");
        return sb.toString();
    }

    public static String buildCreateIndexes(String qualifiedTable, Map<String, IndexSpec> indexes,
                                            Collection<String> primaryKeys, DatabaseType target) {
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (IndexSpec idx : indexes.values()) {
            if (idx.name == null || idx.columns.isEmpty()) {
                continue;
            }
            String upper = idx.name.toUpperCase(Locale.ROOT);
            if ("PRIMARY".equals(upper) || upper.startsWith("PK_") || upper.contains("$SYS")) {
                continue;
            }
            if (primaryKeys != null && idx.columns.size() == primaryKeys.size()
                    && idx.columns.stream().map(c -> c.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList())
                    .containsAll(primaryKeys.stream().map(c -> c.toLowerCase(Locale.ROOT))
                            .collect(Collectors.toList()))) {
                continue;
            }
            String idxName = idx.name.length() > 60 ? idx.name.substring(0, 60) : idx.name;
            sb.append(idx.unique ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
                    .append(quoteIdent(idxName, target))
                    .append(" ON ").append(qualifiedTable).append(" (")
                    .append(idx.columns.stream().map(c -> quoteIdent(c, target))
                            .collect(Collectors.joining(", ")))
                    .append(");\n");
        }
        return sb.toString();
    }

    public static String buildInsert(String qualifiedTable, List<ExportColumn> columns,
                                     List<Object> values, DatabaseType target) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(quoteIdent(columns.get(i).name, target));
            Object v = i < values.size() ? values.get(i) : null;
            vals.append(formatLiteral(v, columns.get(i).jdbcType, target));
        }
        return "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + vals + ");\n";
    }

    public static String fileName(String dsName, String kind, DatabaseType target) {
        String safeDs = (dsName == null || dsName.isEmpty() ? "ds" : dsName)
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        return safeDs + "_" + kind.toLowerCase(Locale.ROOT) + "_"
                + target.name().toLowerCase(Locale.ROOT) + "_" + ts + ".sql";
    }

    public static int clampMaxRows(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(requested, HARD_MAX_ROWS);
    }

    private static boolean supportsIdentity(DatabaseType target) {
        return target != DatabaseType.SQLITE;
    }

    private static String identityClause(DatabaseType target) {
        switch (target) {
            case MYSQL:
            case GOLDENDB:
                return "AUTO_INCREMENT";
            case SQLSERVER:
                return "IDENTITY(1,1)";
            case POSTGRESQL:
            case ORACLE:
                return "GENERATED BY DEFAULT AS IDENTITY";
            case H2:
                return "AUTO_INCREMENT";
            default:
                return "";
        }
    }

    private static boolean isBoolean(int jdbc, String typeName) {
        return jdbc == Types.BOOLEAN || jdbc == Types.BIT
                || "BOOL".equals(typeName) || "BOOLEAN".equals(typeName);
    }

    private static boolean isNumericJdbc(int jdbcType) {
        return jdbcType == Types.TINYINT || jdbcType == Types.SMALLINT || jdbcType == Types.INTEGER
                || jdbcType == Types.BIGINT || jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC
                || jdbcType == Types.FLOAT || jdbcType == Types.REAL || jdbcType == Types.DOUBLE;
    }

    private static boolean isPlainNumber(String s) {
        return s != null && s.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }

    private static boolean isBooleanText(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        return "true".equals(t) || "false".equals(t) || "1".equals(t) || "0".equals(t);
    }

    private static String formatBoolean(boolean v, DatabaseType target) {
        switch (target) {
            case POSTGRESQL:
            case H2:
                return v ? "TRUE" : "FALSE";
            default:
                return v ? "1" : "0";
        }
    }

    private static String formatDate(LocalDate d, DatabaseType target) {
        String raw = d.format(DT);
        switch (target) {
            case ORACLE:
                return "DATE '" + raw + "'";
            default:
                return "'" + raw + "'";
        }
    }

    private static String formatTimestamp(LocalDateTime t, DatabaseType target) {
        String raw = t.format(TS);
        switch (target) {
            case ORACLE:
            case POSTGRESQL:
            case H2:
                return "TIMESTAMP '" + raw + "'";
            default:
                return "'" + raw + "'";
        }
    }

    private static String formatBytes(byte[] data, DatabaseType target) {
        if (data.length > 65_536) {
            return "NULL /* blob truncated */";
        }
        String hex = bytesToHex(data);
        switch (target) {
            case POSTGRESQL:
                return "E'\\\\x" + hex + "'";
            case MYSQL:
            case GOLDENDB:
                return "0x" + hex;
            default:
                return "X'" + hex + "'";
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    static String quoteString(String s, DatabaseType target) {
        String escaped = s.replace("'", "''");
        if (target == DatabaseType.MYSQL || target == DatabaseType.GOLDENDB) {
            escaped = escaped.replace("\\", "\\\\");
        }
        return "'" + escaped + "'";
    }

    public static Map<String, IndexSpec> newIndexMap() {
        return new LinkedHashMap<>();
    }

    public static class ExportColumn {
        public final String name;
        public final int jdbcType;
        public final String typeName;
        public final int size;
        public final int decimalDigits;
        public final boolean nullable;
        public final boolean autoIncrement;

        public ExportColumn(String name, int jdbcType, String typeName, int size,
                            int decimalDigits, boolean nullable, boolean autoIncrement) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.size = size;
            this.decimalDigits = decimalDigits;
            this.nullable = nullable;
            this.autoIncrement = autoIncrement;
        }
    }

    public static class IndexSpec {
        public final String name;
        public final boolean unique;
        public final List<String> columns = new ArrayList<>();

        public IndexSpec(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }
}
