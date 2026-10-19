package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.sql.ViewerSqlScriptBuilder;
import com.gcoll.dbcompare.sql.ViewerSqlScriptBuilder.ExportColumn;
import com.gcoll.dbcompare.sql.ViewerSqlScriptBuilder.IndexSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据浏览：按勾选表 / 选中行导出 DDL、DML，可指定目标库方言。
 */
@Service
@Slf4j
public class DataViewerExportService {

    public static final int MAX_TABLES = 200;

    @Autowired
    private DataSourceService dataSourceService;

    public ExportFile export(Long dsId, ViewerExportRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        String kind = normalizeKind(req.getKind());
        if (req.getTables() == null || req.getTables().isEmpty()) {
            throw new IllegalArgumentException("请选择要导出的表");
        }
        if (req.getTables().size() > MAX_TABLES) {
            throw new IllegalArgumentException("一次最多导出 " + MAX_TABLES + " 张表");
        }
        for (ViewerExportRequest.TableRef ref : req.getTables()) {
            if (ref == null || ref.getName() == null || ref.getName().trim().isEmpty()) {
                throw new IllegalArgumentException("表名不能为空");
            }
        }

        DataSourceConfig ds = dataSourceService.getDataSourceById(dsId);
        DatabaseType sourceType = ds.getDbType();
        DatabaseType targetType = resolveTarget(req.getTargetDbType(), sourceType);
        int maxRows = ViewerSqlScriptBuilder.clampMaxRows(req.getMaxRows());
        boolean dmlFromRows = req.getRows() != null && !req.getRows().isEmpty();
        if (dmlFromRows && req.getTables().size() != 1) {
            throw new IllegalArgumentException("按行导出 DML 时只能选择一张表");
        }
        if (dmlFromRows && "DDL".equals(kind)) {
            throw new IllegalArgumentException("选中行只能导出 DML");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- db-compare 数据浏览导出\n");
        sql.append("-- 数据源: ").append(ds.getName()).append(" (").append(sourceType).append(")\n");
        sql.append("-- 目标方言: ").append(targetType.getDisplayName()).append('\n');
        sql.append("-- 类型: ").append(kind).append('\n');
        sql.append("-- 时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sql.append('\n');

        try (Connection conn = dataSourceService.createConnection(ds)) {
            for (ViewerExportRequest.TableRef ref : req.getTables()) {
                String table = ref.getName().trim();
                String catalog = emptyToNull(ref.getCatalog());
                String schema = emptyToNull(ref.getSchema());
                TableExportMeta meta = readTableMeta(conn, catalog, schema, table);
                if (meta.columns.isEmpty()) {
                    throw new IllegalArgumentException("表不存在或无列: " + table);
                }
                String exportTableName = meta.physicalName != null ? meta.physicalName : table;
                String qualified = ViewerSqlScriptBuilder.qualifyTable(
                        sameNamespace(sourceType, targetType) ? catalog : null,
                        sameNamespace(sourceType, targetType) ? schema : null,
                        exportTableName, targetType);

                sql.append("-- ---------- ").append(table).append(" ----------\n");
                if ("DDL".equals(kind) || "BOTH".equals(kind)) {
                    if (req.isIncludeDrop()) {
                        sql.append(ViewerSqlScriptBuilder.buildDropTable(qualified, targetType));
                    }
                    sql.append(ViewerSqlScriptBuilder.buildCreateTable(
                            qualified, meta.columns, meta.primaryKeys, targetType));
                    sql.append(ViewerSqlScriptBuilder.buildCreateIndexes(
                            qualified, meta.indexes, meta.primaryKeys, targetType));
                    sql.append('\n');
                }
                if ("DML".equals(kind) || "BOTH".equals(kind)) {
                    String sourceQualified = ViewerSqlScriptBuilder.qualifyTable(
                            catalog, schema, exportTableName, sourceType);
                    if (dmlFromRows) {
                        appendSelectedRows(sql, qualified, meta, req.getRows(), targetType);
                    } else {
                        int written = appendTableRows(sql, conn, sourceQualified, qualified, meta, targetType, maxRows);
                        if (written >= maxRows) {
                            sql.append("-- truncated at ").append(maxRows).append(" rows\n");
                        }
                    }
                    sql.append('\n');
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出 SQL 失败: dsId={}, kind={}", dsId, kind, e);
            throw new IllegalStateException("导出失败: " + e.getMessage(), e);
        }

        String fileName = ViewerSqlScriptBuilder.fileName(ds.getName(), kind, targetType);
        return new ExportFile(fileName, sql.toString());
    }

    public List<Map<String, String>> listDialects() {
        List<Map<String, String>> list = new ArrayList<>();
        for (DatabaseType t : DatabaseType.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", t.name());
            row.put("label", t.getDisplayName());
            list.add(row);
        }
        return list;
    }

    private void appendSelectedRows(StringBuilder sql, String qualified, TableExportMeta meta,
                                    List<Map<String, Object>> rows, DatabaseType target) {
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (ExportColumn col : meta.columns) {
                values.add(lookup(row, col.name));
            }
            sql.append(ViewerSqlScriptBuilder.buildInsert(qualified, meta.columns, values, target));
        }
    }

    private int appendTableRows(StringBuilder sql, Connection conn, String sourceQualified,
                                String targetQualified, TableExportMeta meta,
                                DatabaseType target, int maxRows) throws SQLException {
        String select = "SELECT * FROM " + sourceQualified;
        int written = 0;
        try (Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(500);
            stmt.setQueryTimeout(120);
            try (ResultSet rs = stmt.executeQuery(select)) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();
                while (rs.next() && written < maxRows) {
                    List<Object> values = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        values.add(rs.getObject(i));
                    }
                    List<ExportColumn> ordered = alignColumns(meta.columns, rsmd);
                    sql.append(ViewerSqlScriptBuilder.buildInsert(targetQualified, ordered, values, target));
                    written++;
                }
            }
        }
        return written;
    }

    private List<ExportColumn> alignColumns(List<ExportColumn> metaCols, ResultSetMetaData rsmd)
            throws SQLException {
        List<ExportColumn> ordered = new ArrayList<>();
        int n = rsmd.getColumnCount();
        for (int i = 1; i <= n; i++) {
            String label = rsmd.getColumnLabel(i);
            ExportColumn found = null;
            for (ExportColumn c : metaCols) {
                if (c.name.equalsIgnoreCase(label)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                found = new ExportColumn(label, rsmd.getColumnType(i), rsmd.getColumnTypeName(i),
                        rsmd.getColumnDisplaySize(i), rsmd.getScale(i), true, false);
            }
            ordered.add(found);
        }
        return ordered;
    }

    private TableExportMeta readTableMeta(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String resolved = resolveTableName(md, catalog, schema, table);
        TableExportMeta meta = new TableExportMeta();
        meta.physicalName = resolved;
        try (ResultSet rs = md.getColumns(catalog, schema, resolved, "%")) {
            while (rs.next()) {
                boolean autoInc = false;
                try {
                    autoInc = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                } catch (SQLException ignored) {
                    // some drivers omit the column
                }
                meta.columns.add(new ExportColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        autoInc));
            }
        }
        try (ResultSet pk = md.getPrimaryKeys(catalog, schema, resolved)) {
            Map<Integer, String> ordered = new LinkedHashMap<>();
            while (pk.next()) {
                ordered.put(pk.getInt("KEY_SEQ"), pk.getString("COLUMN_NAME"));
            }
            List<Integer> keys = new ArrayList<>(ordered.keySet());
            keys.sort(Integer::compareTo);
            for (Integer k : keys) {
                meta.primaryKeys.add(ordered.get(k));
            }
        }
        try (ResultSet idx = md.getIndexInfo(catalog, schema, resolved, false, true)) {
            while (idx.next()) {
                String name = idx.getString("INDEX_NAME");
                String col = idx.getString("COLUMN_NAME");
                if (name == null || col == null) {
                    continue;
                }
                boolean unique = !idx.getBoolean("NON_UNIQUE");
                IndexSpec spec = meta.indexes.get(name);
                if (spec == null) {
                    spec = new IndexSpec(name, unique);
                    meta.indexes.put(name, spec);
                }
                if (!spec.columns.contains(col)) {
                    spec.columns.add(col);
                }
            }
        } catch (SQLException e) {
            log.debug("读取索引失败 {}: {}", table, e.getMessage());
        }
        return meta;
    }

    private String resolveTableName(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        String fromTables = findPhysicalTableName(md, catalog, schema, table);
        if (fromTables != null) {
            return fromTables;
        }
        if (hasColumns(md, catalog, schema, table)) {
            return table;
        }
        String upper = table.toUpperCase(Locale.ROOT);
        if (!upper.equals(table) && hasColumns(md, catalog, schema, upper)) {
            return upper;
        }
        String lower = table.toLowerCase(Locale.ROOT);
        if (!lower.equals(table) && !lower.equals(upper) && hasColumns(md, catalog, schema, lower)) {
            return lower;
        }
        return table;
    }

    private String findPhysicalTableName(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        try (ResultSet rs = md.getTables(catalog, schema, table, new String[]{"TABLE", "VIEW"})) {
            if (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null) {
                    return name;
                }
            }
        }
        for (String candidate : new String[]{table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            if (candidate.equals(table)) {
                continue;
            }
            try (ResultSet rs = md.getTables(catalog, schema, candidate, new String[]{"TABLE", "VIEW"})) {
                if (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    private boolean hasColumns(DatabaseMetaData md, String catalog, String schema, String table)
            throws SQLException {
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
            return rs.next();
        }
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.trim().isEmpty()) {
            throw new IllegalArgumentException("导出类型不能为空");
        }
        String k = kind.trim().toUpperCase(Locale.ROOT);
        if ("DDL".equals(k) || "DML".equals(k) || "BOTH".equals(k)) {
            return k;
        }
        throw new IllegalArgumentException("导出类型须为 DDL、DML 或 BOTH");
    }

    private static DatabaseType resolveTarget(String target, DatabaseType source) {
        if (target == null || target.trim().isEmpty() || "SOURCE".equalsIgnoreCase(target.trim())) {
            return source;
        }
        return DatabaseType.fromString(target.trim());
    }

    private static boolean sameNamespace(DatabaseType source, DatabaseType target) {
        return source == target;
    }

    private static String emptyToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    private static Object lookup(Map<String, Object> row, String col) {
        if (row.containsKey(col)) {
            return row.get(col);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(col)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static class ExportFile {
        private final String fileName;
        private final String sql;

        public ExportFile(String fileName, String sql) {
            this.fileName = fileName;
            this.sql = sql;
        }

        public String getFileName() {
            return fileName;
        }

        public String getSql() {
            return sql;
        }
    }

    private static class TableExportMeta {
        private String physicalName;
        private final List<ExportColumn> columns = new ArrayList<>();
        private final List<String> primaryKeys = new ArrayList<>();
        private final Map<String, IndexSpec> indexes = ViewerSqlScriptBuilder.newIndexMap();
    }
}
