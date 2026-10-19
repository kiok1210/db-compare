package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 数据浏览服务
 * 提供库表查看、SQL 查询、分页功能
 */
@Service
@Slf4j
public class DataViewerService {

    private static final int MAX_PAGE_SIZE = 500;
    private static final Set<String> DANGEROUS_KEYWORDS = new HashSet<>(Arrays.asList(
        "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE", "TRUNCATE",
        "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "MERGE", "REPLACE"
    ));

    @Autowired
    private DatasourceConfigRepository DatasourceConfigRepository;

    @Autowired
    private DataSourceService dataSourceService;

    /**
     * 获取所有数据源（供页面选择）
     */
    public List<DataSourceConfig> getAllDataSources() {
        return DatasourceConfigRepository.findAll();
    }

    /**
     * 获取数据源的 schema/catalog 列表（用于按库/模式浏览）
     */
    public Map<String, Object> getSchemas(Long dsId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            try (Connection conn = dataSourceService.createConnection(ds)) {
                DatabaseMetaData meta = conn.getMetaData();
                List<Map<String, Object>> schemas = new ArrayList<>();

                // MySQL: catalogs = 数据库列表, schema = 数据库
                // Oracle: schema = 用户
                // PG: schema = schema
                // SQL Server: catalog = 数据库, schema = dbo/...
                DatabaseType dbType = ds.getDbType();

                if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
                    // MySQL: catalog 即数据库名
                    try (ResultSet rs = meta.getCatalogs()) {
                        while (rs.next()) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("name", rs.getString("TABLE_CAT"));
                            s.put("type", "catalog");
                            schemas.add(s);
                        }
                    }
                } else if (dbType == DatabaseType.ORACLE) {
                    // Oracle: schema = owner/user
                    try (ResultSet rs = meta.getSchemas()) {
                        while (rs.next()) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("name", rs.getString("TABLE_SCHEM"));
                            s.put("type", "schema");
                            schemas.add(s);
                        }
                    }
                } else {
                    // PG / SQL Server / H2 / SQLite: schema
                    try (ResultSet rs = meta.getSchemas()) {
                        while (rs.next()) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("name", rs.getString("TABLE_SCHEM"));
                            s.put("type", "schema");
                            schemas.add(s);
                        }
                    }
                }

                // 如果数据源配置了默认 schema，标记为默认
                String defaultSchema = ds.getSchema();
                if (defaultSchema != null && !defaultSchema.trim().isEmpty()) {
                    for (Map<String, Object> s : schemas) {
                        if (defaultSchema.equalsIgnoreCase((String) s.get("name"))) {
                            s.put("isDefault", true);
                            break;
                        }
                    }
                }

                result.put("success", true);
                result.put("schemas", schemas);
                result.put("total", schemas.size());
                result.put("defaultSchema", defaultSchema);
                result.put("dbType", dbType != null ? dbType.name() : "");
                result.put("dbTypeLabel", dbType != null ? dbType.getDisplayName() : "");
            }
        } catch (Exception e) {
            log.error("获取schema列表失败: dsId={}", dsId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 获取指定数据源/模式下的表列表
     */
    public Map<String, Object> getTables(Long dsId, String catalog, String schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            try (Connection conn = dataSourceService.createConnection(ds)) {
                List<Map<String, Object>> tables = new ArrayList<>();
                DatabaseMetaData meta = conn.getMetaData();

                try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", rs.getString("TABLE_NAME"));
                        t.put("type", rs.getString("TABLE_TYPE"));
                        t.put("catalog", rs.getString("TABLE_CAT"));
                        t.put("schema", rs.getString("TABLE_SCHEM"));
                        tables.add(t);
                    }
                }

                result.put("success", true);
                result.put("tables", tables);
                result.put("total", tables.size());
                result.put("catalog", catalog);
                result.put("schema", schema);
            }
        } catch (Exception e) {
            log.error("获取表列表失败: dsId={}, catalog={}, schema={}", dsId, catalog, schema, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 获取表列信息
     */
    public Map<String, Object> getColumns(Long dsId, String table, String catalog, String schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            try (Connection conn = dataSourceService.createConnection(ds)) {
                DatabaseMetaData meta = conn.getMetaData();
                List<Map<String, Object>> columns = new ArrayList<>();

                // 获取主键
                Set<String> primaryKeys = new HashSet<>();
                try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schema, table)) {
                    while (pkRs.next()) {
                        primaryKeys.add(pkRs.getString("COLUMN_NAME"));
                    }
                }

                try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
                    while (rs.next()) {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", rs.getString("COLUMN_NAME"));
                        col.put("type", rs.getString("TYPE_NAME"));
                        col.put("size", rs.getInt("COLUMN_SIZE"));
                        col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                        col.put("isPk", primaryKeys.contains(rs.getString("COLUMN_NAME")));
                        columns.add(col);
                    }
                }

                result.put("success", true);
                result.put("columns", columns);
            }
        } catch (Exception e) {
            log.error("获取列信息失败: dsId={}, table={}", dsId, table, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 分页查询表数据
     */
    public Map<String, Object> queryTableData(Long dsId, String table, int page, int pageSize, String catalog, String schema) {
        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            // 如果 catalog/schema 都为空（前端未选择），尝试从连接获取默认值
            if ((catalog == null || catalog.trim().isEmpty()) && (schema == null || schema.trim().isEmpty())) {
                catalog = getDefaultCatalogSchema(ds);
            }
            String qualifiedTable = buildQualifiedTableName(catalog, schema, table);
            String sql = "SELECT * FROM " + qualifiedTable;
            return executeSqlInternal(dsId, sql, page, Math.min(pageSize, MAX_PAGE_SIZE), false);
        } catch (Exception e) {
            return error("获取数据源信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取表记录总数
     */
    public Map<String, Object> getTableCount(Long dsId, String table, String catalog, String schema) {
        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            if ((catalog == null || catalog.trim().isEmpty()) && (schema == null || schema.trim().isEmpty())) {
                catalog = getDefaultCatalogSchema(ds);
            }
            String qualifiedTable = buildQualifiedTableName(catalog, schema, table);
            return executeSqlInternal(dsId, "SELECT COUNT(*) FROM " + qualifiedTable, 1, 1, true);
        } catch (Exception e) {
            return error("获取数据源信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取连接的默认 catalog 或 schema（当用户未手动选择时使用）
     */
    private String getDefaultCatalogSchema(DataSourceConfig ds) {
        try (Connection conn = dataSourceService.createConnection(ds)) {
            DatabaseMetaData meta = conn.getMetaData();
            DatabaseType dbType = ds.getDbType();
            if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
                return conn.getCatalog(); // MySQL: 当前数据库
            } else if (dbType == DatabaseType.ORACLE) {
                return meta.getUserName(); // Oracle: 当前用户
            } else if (dbType == DatabaseType.POSTGRESQL) {
                return conn.getSchema(); // PG: 当前 schema
            } else if (dbType == DatabaseType.SQLSERVER) {
                return conn.getCatalog(); // SQL Server: 当前数据库
            } else if (dbType == DatabaseType.H2) {
                return conn.getSchema(); // H2: 当前 schema
            }
            return null;
        } catch (SQLException e) {
            log.warn("获取默认catalog/schema失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建带 catalog/schema 限定的完整表名
     * MySQL: catalog.table
     * PG: schema.table  (PG 不支持 catalog.table)
     * Oracle: schema.table
     * SQL Server: catalog.schema.table
     */
    private String buildQualifiedTableName(String catalog, String schema, String table) {
        StringBuilder sb = new StringBuilder();
        boolean hasCatalog = catalog != null && !catalog.trim().isEmpty();
        boolean hasSchema = schema != null && !schema.trim().isEmpty();

        if (hasCatalog) {
            sb.append(quoteIdentifier(catalog));
        }
        if (hasSchema) {
            if (sb.length() > 0) sb.append(".");
            sb.append(quoteIdentifier(schema));
        }
        if (sb.length() > 0) sb.append(".");
        sb.append(quoteIdentifier(table));
        return sb.toString();
    }

    /**
     * 执行自定义 SQL 查询
     */
    public Map<String, Object> executeSql(Long dsId, String sql, int page, int pageSize) {
        if (sql == null || sql.trim().isEmpty()) {
            return error("SQL 不能为空");
        }

        String normalized = sql.trim();
        String upper = normalized.toUpperCase().replaceAll("\\s+", " ").trim();
        if (!upper.startsWith("SELECT")) {
            return error("仅支持 SELECT 查询语句");
        }
        if (normalized.contains(";")) {
            return error("不允许执行多条 SQL 语句");
        }

        // 检查危险关键字（如 DROP/DELETE 等）
        for (String kw : DANGEROUS_KEYWORDS) {
            if (upper.indexOf(kw) >= 0) {
                return error("不支持 " + kw + " 操作，仅允许 SELECT 查询");
            }
        }

        // 自定义 SQL 每次都从第 1 页开始（独立查询，不继承上次分页状态）
        return executeSqlInternal(dsId, normalized, 1, Math.min(pageSize, MAX_PAGE_SIZE), false);
    }

    /**
     * 内部 SQL 执行逻辑（带分页）
     */
    private Map<String, Object> executeSqlInternal(Long dsId, String sql, int page, int pageSize, boolean countOnly) {
        Map<String, Object> result = new LinkedHashMap<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
            conn = dataSourceService.createConnection(ds);
            // 使用 FORWARD_ONLY 游标，兼容 SQLite 等仅支持前向游标的数据库
            stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            stmt.setQueryTimeout(30);
            
            // 检测是否支持滚动游标（用于 fallback 统计）
            boolean scrollable = false;
            try {
                scrollable = conn.getMetaData().supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE);
            } catch (Exception ignored) {}

            int safePage = Math.max(page, 1);
            int safeSize = Math.min(pageSize, MAX_PAGE_SIZE);

            if (countOnly) {
                rs = stmt.executeQuery(sql);
                long total = 0;
                if (rs.next()) {
                    total = rs.getLong(1);
                }
                result.put("success", true);
                result.put("total", total);
                return result;
            }

            // 先获取总记录数
            long totalRows = 0;
            try (Statement countStmt = conn.createStatement()) {
                countStmt.setQueryTimeout(30);
                try (ResultSet countRs = countStmt.executeQuery("SELECT COUNT(*) FROM (" + sql + ") _cnt")) {
                    if (countRs.next()) {
                        totalRows = countRs.getLong(1);
                    }
                }
            } catch (Exception e) {
                log.warn("统计总数失败: {}", e.getMessage());
                if (scrollable) {
                    // 支持滚动游标：用滚动方式统计
                    rs = stmt.executeQuery(sql);
                    rs.last();
                    totalRows = rs.getRow();
                    rs.beforeFirst();
                } else {
                    // 不支持滚动游标（如SQLite）：跳过总数，设为-1
                    totalRows = -1;
                    log.debug("数据库不支持滚动游标，跳过总数统计");
                }
            }

            // 计算分页
            long totalPages = (totalRows + safeSize - 1) / safeSize;
            long offset = (long) (safePage - 1) * safeSize;

            // 构建分页 SQL
            String pageSql = buildPageSql(ds, sql, offset, safeSize);
            log.debug("分页SQL: {}", pageSql);

            if (rs == null) {
                // 正常情况或非滚动fallback：直接执行分页SQL
                rs = stmt.executeQuery(pageSql);
            } else {
                // 滚动游标fallback情况：已执行原始SQL，跳转到指定位置
                try {
                    if (offset > 0) {
                        rs.absolute((int) offset);
                    }
                } catch (Exception e) {
                    log.warn("游标定位失败，重新执行分页SQL: {}", e.getMessage());
                    rs.close();
                    rs = stmt.executeQuery(pageSql);
                }
            }

            // 获取列名
            ResultSetMetaData rsmd = rs.getMetaData();
            int colCount = rsmd.getColumnCount();
            List<String> headers = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                headers.add(rsmd.getColumnName(i));
            }

            // 读取数据行
            List<List<Object>> rows = new ArrayList<>();
            int fetched = 0;
            while (rs.next() && fetched < safeSize) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    row.add(val != null ? val.toString() : null);
                }
                rows.add(row);
                fetched++;
            }

            result.put("success", true);
            result.put("columns", headers);
            result.put("rows", rows);
            result.put("total", totalRows);
            result.put("page", safePage);
            result.put("pageSize", safeSize);
            result.put("totalPages", totalPages);

        } catch (Exception e) {
            log.error("SQL执行失败: dsId={}, sql={}", dsId, sql, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
            closeQuietly(conn);
        }

        return result;
    }

    /**
     * 根据不同数据库构建分页 SQL
     */
    private String buildPageSql(DataSourceConfig ds, String sql, long offset, int limit) {
        // 先剥离已有 LIMIT / OFFSET，避免双重分页
        String cleanSql = stripPagination(sql);
        
        String dbType = ds.getDbType() != null ? ds.getDbType().name().toUpperCase() : "";
        switch (dbType) {
            case "MYSQL":
            case "GOLDENDB":
            case "H2":
            case "POSTGRESQL":
                return cleanSql + " LIMIT " + limit + " OFFSET " + offset;
            case "SQLITE":
                // SQLite 不支持 OFFSET 0（某些版本），特殊处理
                if (offset > 0) {
                    return cleanSql + " LIMIT " + limit + " OFFSET " + offset;
                }
                return cleanSql + " LIMIT " + limit;
            case "SQLSERVER":
                if (!cleanSql.toUpperCase().contains("ORDER BY")) {
                    cleanSql += " ORDER BY (SELECT NULL)";
                }
                return cleanSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            case "ORACLE":
                return "SELECT * FROM (SELECT _p.*, ROWNUM _rn FROM (" + cleanSql + ") _p WHERE ROWNUM <= " + (offset + limit) + ") WHERE _rn > " + offset;
            default:
                return cleanSql + " LIMIT " + limit + " OFFSET " + offset;
        }
    }

    /**
     * 剥离 SQL 末尾的 LIMIT / OFFSET 子句
     */
    private String stripPagination(String sql) {
        if (sql == null || sql.trim().isEmpty()) return sql;
        // 匹配末尾的 LIMIT n [OFFSET m] 或 OFFSET m LIMIT n 等（不区分大小写）
        // 使用 (?i) 开启大小写不敏感
        String stripped = sql.replaceAll("(?i)\\s+OFFSET\\s+\\d+\\s*$", "");
        stripped = stripped.replaceAll("(?i)\\s+LIMIT\\s+(?:-?\\d+)\\s*(?:,(?:-?\\d+)?)?\\s*$", "");
        // 再次清理可能的残余 OFFSET（LIMIT 被去掉后可能露出）
        stripped = stripped.replaceAll("(?i)\\s+OFFSET\\s+\\d+\\s*$", "");
        return stripped.trim();
    }

    private String quoteIdentifier(String name) {
        if (name == null) return "";
        if (name.matches("^[a-zA-Z_]\\w*$")) return name;
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("msg", msg);
        return r;
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
}
