package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.sql.DatabaseDialect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主键增量合并（UPSERT）SQL 构建。
 * MySQL/GoldenDB：INSERT … ON DUPLICATE KEY UPDATE；其他库由调用方走 UPDATE/INSERT 回退。
 */
public final class SyncUpsertHelper {

    private SyncUpsertHelper() {
    }

    public static boolean supportsNativeUpsert(DatabaseType dbType) {
        return dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB;
    }

    /**
     * 构建 MySQL/GoldenDB 的 INSERT … ON DUPLICATE KEY UPDATE。
     * 更新列排除主键列（主键冲突时只刷新非主键字段）。
     */
    public static String buildMysqlOnDuplicateUpsert(String qualifiedTable,
                                                     List<String> columns,
                                                     List<String> primaryKeys,
                                                     DatabaseType dbType) {
        if (qualifiedTable == null || columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("表名与列不能为空");
        }
        Set<String> pkLower = new LinkedHashSet<>();
        if (primaryKeys != null) {
            for (String pk : primaryKeys) {
                if (pk != null && !pk.trim().isEmpty()) {
                    pkLower.add(pk.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                placeholders.append(", ");
            }
            cols.append(DatabaseDialect.escapeIdentifier(columns.get(i), dbType));
            placeholders.append("?");
        }
        List<String> updateParts = new ArrayList<>();
        for (String col : columns) {
            if (col == null) {
                continue;
            }
            if (pkLower.contains(col.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String q = DatabaseDialect.escapeIdentifier(col, dbType);
            updateParts.add(q + "=VALUES(" + q + ")");
        }
        // 极端情况：表只有主键列，仍写一个无害赋值保证语法合法
        if (updateParts.isEmpty() && !columns.isEmpty()) {
            String q = DatabaseDialect.escapeIdentifier(columns.get(0), dbType);
            updateParts.add(q + "=" + q);
        }
        return "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + placeholders + ")"
                + " ON DUPLICATE KEY UPDATE " + String.join(", ", updateParts);
    }

    /** 非主键列列表（供 UPDATE SET） */
    public static List<String> nonPrimaryColumns(List<String> columns, List<String> primaryKeys) {
        Set<String> pkLower = new LinkedHashSet<>();
        if (primaryKeys != null) {
            for (String pk : primaryKeys) {
                if (pk != null && !pk.trim().isEmpty()) {
                    pkLower.add(pk.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> out = new ArrayList<>();
        if (columns == null) {
            return out;
        }
        for (String col : columns) {
            if (col == null) {
                continue;
            }
            if (!pkLower.contains(col.toLowerCase(Locale.ROOT))) {
                out.add(col);
            }
        }
        return out;
    }
}
