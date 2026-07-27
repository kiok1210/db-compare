package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL 安全辅助：标识符转义、PreparedStatement 条件绑定
 */
public final class SqlHelper {

    private SqlHelper() {
    }

    public static String quoteIdentifier(String identifier, DatabaseType dbType) {
        if (identifier == null) {
            return "";
        }
        return DatabaseDialect.escapeIdentifier(identifier, dbType);
    }

    /**
     * 构建 WHERE 子句（列 = ?）及参数列表，供 PreparedStatement 绑定
     */
    public static WhereClause buildWhereClause(List<String> keys, Map<String, Object> row, DatabaseType dbType) {
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<Integer> types = new ArrayList<>();

        for (String key : keys) {
            // Case-insensitive lookup: try exact match first, then scan for case-insensitive match
            Object value = row.get(key);
            if (value == null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(key)) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            if (value == null) {
                parts.add(quoteIdentifier(key, dbType) + " IS NULL");
            } else {
                parts.add(quoteIdentifier(key, dbType) + " = ?");
                params.add(value);
                types.add(sqlType(value));
            }
        }

        WhereClause clause = new WhereClause();
        clause.setSql(String.join(" AND ", parts));
        clause.setParams(params);
        clause.setTypes(types);
        return clause;
    }

    public static void bindParams(PreparedStatement pstmt, WhereClause clause, int startIndex) throws SQLException {
        for (int i = 0; i < clause.getParams().size(); i++) {
            Object param = clause.getParams().get(i);
            int type = clause.getTypes().get(i);
            if (type == Types.TIMESTAMP && param instanceof java.util.Date) {
                pstmt.setTimestamp(startIndex + i, new java.sql.Timestamp(((java.util.Date) param).getTime()));
            } else {
                pstmt.setObject(startIndex + i, param, type);
            }
        }
    }

    private static int sqlType(Object value) {
        if (value instanceof Integer) {
            return Types.INTEGER;
        }
        if (value instanceof Long) {
            return Types.BIGINT;
        }
        if (value instanceof java.util.Date || value instanceof java.sql.Timestamp) {
            return Types.TIMESTAMP;
        }
        if (value instanceof java.math.BigDecimal) {
            return Types.DECIMAL;
        }
        if (value instanceof Double || value instanceof Float) {
            return Types.DOUBLE;
        }
        return Types.VARCHAR;
    }

    public static class WhereClause {
        private String sql;
        private List<Object> params = new ArrayList<>();
        private List<Integer> types = new ArrayList<>();

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public List<Object> getParams() {
            return params;
        }

        public void setParams(List<Object> params) {
            this.params = params;
        }

        public List<Integer> getTypes() {
            return types;
        }

        public void setTypes(List<Integer> types) {
            this.types = types;
        }
    }
}
