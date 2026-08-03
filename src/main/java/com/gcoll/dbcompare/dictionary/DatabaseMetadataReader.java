package com.gcoll.dbcompare.dictionary;

import com.gcoll.dbcompare.entity.DatabaseType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据库元数据读取器（适配不同数据库）
 * 支持：MySQL、PostgreSQL、Oracle、GoldenDB
 */
@Slf4j
public class DatabaseMetadataReader {
    
    /**
     * 读取所有表名（适配不同数据库）
     */
    public static List<String> readAllTables(Connection conn, String schema, DatabaseType dbType) 
            throws SQLException {
        return readAllTables(conn, null, schema, dbType);
    }

    /**
     * 读取所有表名（支持显式指定 catalog 和 dbSchema）
     */
    public static List<String> readAllTables(Connection conn, String catalog, String dbSchema, DatabaseType dbType) 
            throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        
        // 如果有显式传 catalog/dbSchema，直接使用；否则根据数据库类型推断
        String useCatalog = catalog;
        String useSchema = dbSchema;
        
        if (useCatalog == null && useSchema == null) {
            // 兼容旧逻辑：从 schema 参数推断
            useSchema = dbSchema != null ? dbSchema : null;
        }
        
        // 不同数据库的处理方式
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                // 如果没显式指定 catalog 和 schema（即"全库"模式），枚举所有 catalog
                if (useCatalog == null) {
                    if (useSchema != null) {
                        useCatalog = useSchema;
                        useSchema = null;
                    } else {
                        // "全库"模式：枚举所有非系统 catalog
                        List<String> catalogs = getNonSystemCatalogs(metaData);
                        for (String cat : catalogs) {
                            try (ResultSet rs = metaData.getTables(cat, null, "%", new String[]{"TABLE"})) {
                                while (rs.next()) {
                                    tables.add(cat + "::" + rs.getString("TABLE_NAME"));
                                }
                            }
                        }
                        log.info("数据库类型: {}, 全库模式, 发现 {} 个数据库, 共 {} 张表", 
                                 dbType.getDisplayName(), catalogs.size(), tables.size());
                        return tables;
                    }
                }
                break;
            
            case POSTGRESQL:
                if (useSchema == null || useSchema.isEmpty()) {
                    useSchema = "public";
                }
                break;
            
            case ORACLE:
                if (useSchema != null) {
                    useSchema = useSchema.toUpperCase();
                }
                break;
            
            case SQLSERVER:
                break;

            case H2:
                // 未指定 schema 时默认 PUBLIC；显式传入（含全库枚举）时勿覆盖
                if (useSchema == null || useSchema.isEmpty()) {
                    useSchema = "PUBLIC";
                }
                break;

            case SQLITE:
                // SQLite: 使用 sqlite_master 查询
                try (java.sql.Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                    while (rs.next()) {
                        tables.add(rs.getString("name"));
                    }
                }
                log.info("数据库类型: {}, 共发现 {} 张表", dbType.getDisplayName(), tables.size());
                return tables;
        }
        
        try (ResultSet rs = metaData.getTables(useCatalog, useSchema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        
        log.info("数据库类型: {}, catalog={}, schema={}, 共发现 {} 张表", 
                 dbType.getDisplayName(), useCatalog, useSchema, tables.size());
        return tables;
    }

    /**
     * 获取非系统 catalog 列表（用于 MySQL/GoldenDB "全库"模式）
     */
    private static final Set<String> MYSQL_SYSTEM_CATALOGS = new HashSet<>(Arrays.asList(
        "information_schema", "mysql", "performance_schema", "sys", "_gdb_sysdb"
    ));

    public static boolean isSystemCatalog(String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase();
        return MYSQL_SYSTEM_CATALOGS.contains(lower) || lower.startsWith("_gdb_");
    }

    private static List<String> getNonSystemCatalogs(DatabaseMetaData metaData) throws SQLException {
        List<String> catalogs = new ArrayList<>();
        try (ResultSet rs = metaData.getCatalogs()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_CAT");
                if (name != null && !isSystemCatalog(name)) {
                    catalogs.add(name);
                }
            }
        }
        return catalogs;
    }

    /**
     * 从"全库"模式收集所有 schema 下的所有表（以 schema::table 格式返回）
     * 用于 TableFilterHelper 处理空 schema 的情况
     */
    public static List<String> readAllTablesFromAllSchemas(Connection conn, DatabaseType dbType) throws SQLException {
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<String> tables = new ArrayList<>();
            List<String> catalogs = getNonSystemCatalogs(metaData);
            for (String cat : catalogs) {
                try (ResultSet rs = metaData.getTables(cat, null, "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        tables.add(cat + "::" + rs.getString("TABLE_NAME"));
                    }
                }
            }
            log.info("全库模式: 发现 {} 个数据库, 共 {} 张表", catalogs.size(), tables.size());
            return tables;
        }
        if (dbType == DatabaseType.H2 || dbType == DatabaseType.POSTGRESQL) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<String> tables = new ArrayList<>();
            int schemaCount = 0;
            try (ResultSet schemas = metaData.getSchemas()) {
                while (schemas.next()) {
                    String schemaName = schemas.getString("TABLE_SCHEM");
                    if (schemaName == null || isSystemSchema(schemaName)) {
                        continue;
                    }
                    schemaCount++;
                    try (ResultSet rs = metaData.getTables(null, schemaName, "%", new String[]{"TABLE"})) {
                        while (rs.next()) {
                            tables.add(schemaName + "::" + rs.getString("TABLE_NAME"));
                        }
                    }
                }
            }
            log.info("全库模式({}): 发现 {} 个 schema, 共 {} 张表", dbType, schemaCount, tables.size());
            return tables;
        }
        // SQLITE 等：单库文件，无多 schema 枚举
        return readAllTables(conn, (String) null, dbType);
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return true;
        }
        String lower = schema.toLowerCase();
        return "information_schema".equals(lower)
                || "pg_catalog".equals(lower)
                || "pg_toast".equals(lower)
                || "sys".equals(lower);
    }
    
    /**
     * 读取表元数据（适配不同数据库）
     */
    public static TableMetadata readTableMetadata(Connection conn, String tableName, DatabaseType dbType) 
            throws SQLException {
        return readTableMetadata(conn, tableName, dbType, null);
    }

    /**
     * 读取表元数据（指定 catalog，用于多库模式）
     */
    public static TableMetadata readTableMetadata(Connection conn, String tableName, DatabaseType dbType, String catalog) 
            throws SQLException {
        TableMetadata metadata = new TableMetadata();
        metadata.setTableName(tableName);
        metadata.setDatabaseType(dbType);
        
        DatabaseMetaData dbMetaData = conn.getMetaData();
        String metaTableName = resolveMetaTableName(dbMetaData, catalog, tableName, dbType);
        
        // 读取列信息
        List<ColumnMetadata> columns = new ArrayList<>();

        // SQLite 使用 PRAGMA table_info 获取列信息
        if (dbType == DatabaseType.SQLITE) {
            try (java.sql.Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info('" + tableName + "')")) {
                while (rs.next()) {
                    ColumnMetadata col = new ColumnMetadata();
                    col.setColumnName(rs.getString("name"));
                    String type = rs.getString("type");
                    col.setDataType(type != null ? type.toUpperCase() : "TEXT");
                    col.setColumnSize(0);
                    col.setNullable(rs.getInt("notnull") == 0);
                    col.setDefaultValue(rs.getString("dflt_value"));
                    col.setRemarks("");
                    columns.add(col);
                }
            }
        } else {
            try (ResultSet rs = dbMetaData.getColumns(catalog, null, metaTableName, null)) {
                while (rs.next()) {
                    ColumnMetadata col = new ColumnMetadata();
                    col.setColumnName(rs.getString("COLUMN_NAME"));
                    col.setDataType(convertDataType(rs.getString("TYPE_NAME"), dbType));
                    col.setColumnSize(rs.getInt("COLUMN_SIZE"));
                    col.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.setDefaultValue(rs.getString("COLUMN_DEF"));
                    col.setRemarks(rs.getString("REMARKS"));
                    
                    if (dbType == DatabaseType.ORACLE) {
                        col.setPrecision(rs.getInt("COLUMN_SIZE"));
                        col.setScale(rs.getInt("DECIMAL_DIGITS"));
                    }
                    
                    columns.add(col);
                }
            }
        }
        metadata.setColumns(columns);
        
        // 读取主键
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet rs = dbMetaData.getPrimaryKeys(catalog, null, metaTableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }
        metadata.setPrimaryKeys(primaryKeys);
        
        // 读取索引
        List<IndexMetadata> indexes = new ArrayList<>();
        try (ResultSet rs = dbMetaData.getIndexInfo(catalog, null, metaTableName, false, true)) {
            while (rs.next()) {
                IndexMetadata idx = new IndexMetadata();
                idx.setIndexName(rs.getString("INDEX_NAME"));
                idx.setColumnName(rs.getString("COLUMN_NAME"));
                idx.setUnique(!rs.getBoolean("NON_UNIQUE"));
                
                // 不同数据库的索引名处理
                if (idx.getIndexName() != null) {
                    indexes.add(idx);
                }
            }
        }
        metadata.setIndexes(indexes);
        
        log.debug("读取表元数据完成: {} -> {} ({})", tableName, metaTableName, dbType.getDisplayName());
        return metadata;
    }

    /**
     * H2/部分库未加引号标识符会折叠大小写；按原名→大写→小写探测 JDBC 元数据中的真实表名。
     */
    private static String resolveMetaTableName(DatabaseMetaData dbMetaData, String catalog,
                                               String tableName, DatabaseType dbType) throws SQLException {
        if (tableName == null || tableName.isEmpty() || dbType == DatabaseType.SQLITE) {
            return tableName;
        }
        if (hasColumns(dbMetaData, catalog, tableName)) {
            return tableName;
        }
        String upper = tableName.toUpperCase(Locale.ROOT);
        if (!upper.equals(tableName) && hasColumns(dbMetaData, catalog, upper)) {
            return upper;
        }
        String lower = tableName.toLowerCase(Locale.ROOT);
        if (!lower.equals(tableName) && !lower.equals(upper) && hasColumns(dbMetaData, catalog, lower)) {
            return lower;
        }
        return tableName;
    }

    private static boolean hasColumns(DatabaseMetaData dbMetaData, String catalog, String tableName)
            throws SQLException {
        try (ResultSet rs = dbMetaData.getColumns(catalog, null, tableName, null)) {
            return rs.next();
        }
    }
    
    /**
     * 数据类型转换（统一不同数据库的数据类型名称）
     */
    private static String convertDataType(String originalType, DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return convertMySQLType(originalType);
            
            case POSTGRESQL:
                return convertPostgreSQLType(originalType);
            
            case ORACLE:
                return convertOracleType(originalType);
            
            default:
                return originalType;
        }
    }
    
    /**
     * MySQL/GoldenDB 类型转换
     */
    private static String convertMySQLType(String type) {
        // MySQL 和 GoldenDB 类型基本一致
        return type.toUpperCase();
    }
    
    /**
     * PostgreSQL 类型转换
     */
    private static String convertPostgreSQLType(String type) {
        // PostgreSQL 类型映射
        switch (type.toLowerCase()) {
            case "integer":
            case "int4":
                return "INT";
            case "bigint":
            case "int8":
                return "BIGINT";
            case "character varying":
            case "varchar":
                return "VARCHAR";
            case "timestamp without time zone":
                return "TIMESTAMP";
            case "timestamp with time zone":
                return "TIMESTAMPTZ";
            default:
                return type.toUpperCase();
        }
    }
    
    /**
     * Oracle 类型转换
     */
    private static String convertOracleType(String type) {
        // Oracle 类型映射
        switch (type.toUpperCase()) {
            case "NUMBER":
                return "NUMERIC";
            case "VARCHAR2":
                return "VARCHAR";
            case "CLOB":
                return "TEXT";
            case "DATE":
                return "TIMESTAMP";
            case "TIMESTAMP":
                return "TIMESTAMP";
            default:
                return type.toUpperCase();
        }
    }
    
    /**
     * 构建分页查询SQL（适配不同数据库）
     */
    public static String buildPaginationSql(String tableName, String orderBy, int offset, int limit, 
                                              DatabaseType dbType) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return String.format("SELECT * FROM %s ORDER BY %s LIMIT %d OFFSET %d", 
                    tableName, orderBy, limit, offset);
            
            case POSTGRESQL:
                return String.format("SELECT * FROM %s ORDER BY %s LIMIT %d OFFSET %d", 
                    tableName, orderBy, limit, offset);
            
            case ORACLE:
                // Oracle 12c+ 支持 FETCH FIRST
                return String.format(
                    "SELECT * FROM (SELECT t.*, ROWNUM rn FROM (SELECT * FROM %s ORDER BY %s) t) " +
                    "WHERE rn > %d AND rn <= %d", 
                    tableName, orderBy, offset, offset + limit);
            
            case SQLSERVER:
                return String.format("SELECT * FROM %s ORDER BY %s OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", 
                    tableName, orderBy, offset, limit);
            
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }
    
    /**
     * 构建随机抽样SQL（适配不同数据库）
     */
    public static String buildSampleSql(String tableName, double sampleRate, DatabaseType dbType) {
        return buildSampleSql(tableName, sampleRate, dbType, Math.max(1, (int) (sampleRate * 1000)));
    }

    public static String buildSampleSql(String tableName, double sampleRate, DatabaseType dbType, int sampleSize) {
        switch (dbType) {
            case MYSQL:
            case GOLDENDB:
                return String.format("SELECT * FROM %s ORDER BY RAND() LIMIT %d", tableName, sampleSize);

            case POSTGRESQL:
                return String.format("SELECT * FROM %s ORDER BY RANDOM() LIMIT %d", tableName, sampleSize);

            case ORACLE:
                return String.format("SELECT * FROM (SELECT * FROM %s ORDER BY DBMS_RANDOM.VALUE) WHERE ROWNUM <= %d",
                    tableName, sampleSize);

            case SQLSERVER:
                return String.format("SELECT TOP %d * FROM %s ORDER BY NEWID()", sampleSize, tableName);

            case H2:
                return String.format("SELECT * FROM %s ORDER BY RAND() LIMIT %d", tableName, sampleSize);

            case SQLITE:
                return String.format("SELECT * FROM %s ORDER BY RANDOM() LIMIT %d", tableName, sampleSize);

            default:
                return String.format("SELECT * FROM %s ORDER BY RAND() LIMIT %d", tableName, sampleSize);
        }
    }
    
    /**
     * 表元数据
     */
    @Data
    public static class TableMetadata {
        private String tableName;
        private DatabaseType databaseType;
        private List<ColumnMetadata> columns;
        private List<String> primaryKeys;
        private List<IndexMetadata> indexes;
    }
    
    /**
     * 列元数据
     */
    @Data
    public static class ColumnMetadata {
        private String columnName;
        private String dataType;
        private int columnSize;
        private boolean nullable;
        private String defaultValue;
        private String remarks;
        private int precision;
        private int scale;
    }
    
    /**
     * 索引元数据
     */
    @Data
    public static class IndexMetadata {
        private String indexName;
        private String columnName;
        private boolean unique;
    }
}
