package com.gcoll.dbcompare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 修复独立数据源库在未启用 Spring 命名策略时建出的驼峰列（如 DBTYPE），
 * 对齐为 snake_case（db_type），避免启动后 SELECT 报 Column not found。
 */
final class DatasourceStoreSchemaRepair {

    private static final Logger log = LoggerFactory.getLogger(DatasourceStoreSchemaRepair.class);

    private DatasourceStoreSchemaRepair() {
    }

    static void repair(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            Set<String> cols = columnNames(conn, "DBCOMPARE_DATASOURCE");
            if (cols.isEmpty()) {
                cols = columnNames(conn, "dbcompare_datasource");
            }
            if (cols.isEmpty()) {
                return;
            }
            boolean changed = false;
            changed |= renameIfNeeded(stmt, cols, "DBTYPE", "DB_TYPE");
            changed |= renameIfNeeded(stmt, cols, "DRIVERCLASS", "DRIVER_CLASS");
            changed |= mergeTimestamp(stmt, cols, "CREATETIME", "CREATE_TIME");
            changed |= mergeTimestamp(stmt, cols, "UPDATETIME", "UPDATE_TIME");
            if (changed) {
                log.info("已修复独立数据源库列名（camelCase → snake_case）");
            }
        } catch (Exception e) {
            log.warn("独立数据源库列名修复跳过: {}", e.getMessage());
        }
    }

    private static boolean renameIfNeeded(Statement stmt, Set<String> cols, String from, String to)
            throws Exception {
        boolean hasFrom = cols.contains(from);
        boolean hasTo = cols.contains(to);
        if (hasFrom && !hasTo) {
            stmt.execute("ALTER TABLE dbcompare_datasource ALTER COLUMN " + from + " RENAME TO " + to);
            cols.remove(from);
            cols.add(to);
            log.info("数据源库列重命名: {} → {}", from, to);
            return true;
        }
        if (hasFrom && hasTo) {
            // 双列并存：把旧列非空值补到新列后删旧列
            stmt.execute("UPDATE dbcompare_datasource SET " + to + " = " + from
                    + " WHERE " + to + " IS NULL AND " + from + " IS NOT NULL");
            try {
                stmt.execute("ALTER TABLE dbcompare_datasource DROP COLUMN " + from);
            } catch (Exception e) {
                log.debug("删除冗余列 {} 失败: {}", from, e.getMessage());
            }
            cols.remove(from);
            return true;
        }
        return false;
    }

    private static boolean mergeTimestamp(Statement stmt, Set<String> cols, String legacy, String snake)
            throws Exception {
        return renameIfNeeded(stmt, cols, legacy, snake);
    }

    private static Set<String> columnNames(Connection conn, String table) throws Exception {
        Set<String> cols = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                String n = rs.getString("COLUMN_NAME");
                if (n != null) {
                    cols.add(n.toUpperCase(Locale.ROOT));
                }
            }
        }
        if (cols.isEmpty()) {
            try (ResultSet rs = meta.getColumns(null, null, table.toLowerCase(Locale.ROOT), null)) {
                while (rs.next()) {
                    String n = rs.getString("COLUMN_NAME");
                    if (n != null) {
                        cols.add(n.toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return cols;
    }
}
