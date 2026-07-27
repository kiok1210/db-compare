package com.gcoll.dbcompare.sql;

import com.gcoll.dbcompare.entity.DatabaseType;

/**
 * 数据库方言适配器
 * 处理不同数据库的SQL语法差异
 */
public class DatabaseDialect {
    
    /**
     * 获取当前数据库类型
     */
    public static DatabaseType detectDatabaseType(String jdbcUrl) {
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("JDBC URL不能为空");
        }
        
        String url = jdbcUrl.toLowerCase();
        
        if (url.contains("mysql")) {
            return DatabaseType.MYSQL;
        } else if (url.contains("postgresql")) {
            return DatabaseType.POSTGRESQL;
        } else if (url.contains("oracle")) {
            return DatabaseType.ORACLE;
        } else if (url.contains("goldendb")) {
            return DatabaseType.GOLDENDB;
        } else if (url.contains("sqlserver") || url.contains("microsoft")) {
            return DatabaseType.SQLSERVER;
        } else if (url.contains("sqlite")) {
            return DatabaseType.SQLITE;
        } else if (url.contains("h2")) {
            return DatabaseType.H2;
        } else {
            throw new IllegalArgumentException("无法识别的数据库类型: " + jdbcUrl);
        }
    }
    
    /**
     * 构建计数查询SQL
     */
    public static String buildCountSql(String tableName, DatabaseType dbType) {
        return String.format("SELECT COUNT(*) FROM %s", tableName);
    }
    
    /**
     * 构建分页查询SQL（offset 用 long，避免大表翻页超过 Integer.MAX_VALUE 被截断）
     */
    public static String buildPaginationSql(String tableName, String primaryKey, 
                                              long offset, int limit, DatabaseType dbType) {
        if (offset < 0) {
            offset = 0;
        }
        if (limit < 1) {
            limit = 1;
        }
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
            case POSTGRESQL:
            case H2:
            case SQLITE:
                return String.format("SELECT * FROM %s ORDER BY %s LIMIT %d OFFSET %d", 
                    tableName, primaryKey, limit, offset);
            
            case ORACLE:
                return String.format(
                    "SELECT * FROM %s ORDER BY %s OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", 
                    tableName, primaryKey, offset, limit);
            
            case SQLSERVER:
                return String.format(
                    "SELECT * FROM %s ORDER BY %s OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", 
                    tableName, primaryKey, offset, limit);
            
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }
    
    /**
     * 构建获取表列表SQL
     */
    public static String buildTableListSql(String schema, DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return String.format("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                    "WHERE TABLE_SCHEMA = '%s' AND TABLE_TYPE = 'BASE TABLE'", schema);
            
            case POSTGRESQL:
                return String.format("SELECT tablename FROM pg_tables WHERE schemaname = '%s'", 
                    schema != null ? schema : "public");
            
            case ORACLE:
                return String.format("SELECT table_name FROM all_tables WHERE owner = '%s'", 
                    schema != null ? schema.toUpperCase() : "");
            
            case SQLSERVER:
                return String.format("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                    "WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA = '%s'", 
                    schema != null ? schema : "dbo");

            case H2:
                return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'TABLE'";

            case SQLITE:
                return "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'";
            
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }
    
    /**
     * 构建获取列信息SQL
     */
    public static String buildColumnInfoSql(String tableName, String schema, DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return String.format("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, " +
                    "IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' " +
                    "ORDER BY ORDINAL_POSITION", schema, tableName);
            
            case POSTGRESQL:
                return String.format("SELECT column_name, data_type, udt_name, " +
                    "is_nullable, column_default, '' as column_comment " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE table_schema = '%s' AND table_name = '%s' " +
                    "ORDER BY ordinal_position", 
                    schema != null ? schema : "public", tableName);
            
            case ORACLE:
                return String.format("SELECT col.column_name, col.data_type, col.data_type as column_type, " +
                    "col.nullable, col.data_default, comm.comments as column_comment " +
                    "FROM all_tab_columns col " +
                    "LEFT JOIN all_col_comments comm " +
                    "ON col.table_name = comm.table_name AND col.column_name = comm.column_name " +
                    "WHERE col.owner = '%s' AND col.table_name = '%s' " +
                    "ORDER BY col.column_id", 
                    schema != null ? schema.toUpperCase() : "", tableName.toUpperCase());

            case H2:
                return String.format("SELECT COLUMN_NAME, TYPE_NAME AS DATA_TYPE, TYPE_NAME AS COLUMN_TYPE, " +
                    "IS_NULLABLE, COLUMN_DEFAULT, REMARKS AS COLUMN_COMMENT " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_NAME = '%s' ORDER BY ORDINAL_POSITION", tableName.toUpperCase());

            case SQLITE:
                return String.format("PRAGMA table_info('%s')", tableName);
            
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }
    
    /**
     * 转义标识符（表名、列名等）
     */
    public static String escapeIdentifier(String identifier, DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return "`" + identifier + "`";
            
            case POSTGRESQL:
            case ORACLE:
                return "\"" + identifier.toUpperCase() + "\"";
            
            case SQLSERVER:
                return "[" + identifier + "]";

            case H2:
            case SQLITE:
                return "\"" + identifier + "\"";
            
            default:
                return identifier;
        }
    }
    
    /**
     * 获取当前时间戳SQL
     */
    public static String getCurrentTimestampSql(DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return "NOW()";
            
            case POSTGRESQL:
                return "CURRENT_TIMESTAMP";
            
            case ORACLE:
                return "SYSTIMESTAMP";
            
            case SQLSERVER:
                return "GETDATE()";

            case H2:
                return "CURRENT_TIMESTAMP";

            case SQLITE:
                return "datetime('now')";
            
            default:
                return "CURRENT_TIMESTAMP";
        }
    }
}
