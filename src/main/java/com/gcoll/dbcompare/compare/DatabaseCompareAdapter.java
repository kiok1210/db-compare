package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.sql.DatabaseDialect;
import com.gcoll.dbcompare.sql.SqlHelper;
import com.gcoll.dbcompare.util.SyncDataSyncHelper;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据库比对适配器
 * 根据不同数据库类型，调用相应的比对逻辑
 */
@Slf4j
public class DatabaseCompareAdapter {
    
    /**
     * 比对两张表的数据量（适配不同数据库）
     */
    public static long compareTableCount(Connection sourceConn, Connection targetConn,
                                          String tableName, DatabaseType dbType) throws SQLException {
        long sourceCount = countTable(sourceConn, tableName, dbType);
        long targetCount = countTable(targetConn, tableName, dbType);
        
        long diff = Math.abs(sourceCount - targetCount);
        if (diff != 0) {
            log.warn("表 {} 数据量不一致: 源库={}, 目标库={}, 差异={}", 
                     tableName, sourceCount, targetCount, diff);
        } else {
            log.info("表 {} 数据量一致: {}", tableName, sourceCount);
        }
        
        return diff;
    }
    
    /**
     * 统计表记录数（适配不同数据库）
     */
    private static long countTable(Connection conn, String tableName, DatabaseType dbType) throws SQLException {
        String sql = DatabaseDialect.buildCountSql(tableName, dbType);
        
        try (java.sql.Statement stmt = conn.createStatement(); java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        
        return 0;
    }
    
    /**
     * 比对表结构（适配不同数据库）
     */
    public static boolean compareTableSchema(Connection sourceConn, Connection targetConn,
                                              String tableName, DatabaseType sourceDbType,
                                              DatabaseType targetDbType,
                                              String sourceCatalog, String targetCatalog) throws SQLException {
        // 读取源库和目标库的表元数据（多库模式传入 catalog）
        DatabaseMetadataReader.TableMetadata sourceMeta =
            DatabaseMetadataReader.readTableMetadata(sourceConn, tableName, sourceDbType, sourceCatalog);
        DatabaseMetadataReader.TableMetadata targetMeta =
            DatabaseMetadataReader.readTableMetadata(targetConn, tableName, targetDbType, targetCatalog);
        
        // 比对列
        if (!compareColumns(sourceMeta.getColumns(), targetMeta.getColumns())) {
            log.warn("表 {} 的列结构不一致", tableName);
            return false;
        }
        
        // 比对主键
        if (!sourceMeta.getPrimaryKeys().equals(targetMeta.getPrimaryKeys())) {
            log.warn("表 {} 的主键不一致: 源库={}, 目标库={}", 
                     tableName, sourceMeta.getPrimaryKeys(), targetMeta.getPrimaryKeys());
            return false;
        }
        
        log.info("表 {} 的结构一致", tableName);
        return true;
    }

    /**
     * 比对表索引（适配不同数据库）
     * @return 索引比对结果 Map，包含一致状态和差异详情
     */
    public static Map<String, Object> compareIndexes(Connection sourceConn, Connection targetConn,
                                                       String tableName, DatabaseType sourceDbType,
                                                       DatabaseType targetDbType,
                                                       String sourceCatalog, String targetCatalog) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        DatabaseMetadataReader.TableMetadata sourceMeta =
                DatabaseMetadataReader.readTableMetadata(sourceConn, tableName, sourceDbType, sourceCatalog);
        DatabaseMetadataReader.TableMetadata targetMeta =
                DatabaseMetadataReader.readTableMetadata(targetConn, tableName, targetDbType, targetCatalog);

        List<DatabaseMetadataReader.IndexMetadata> sourceIndexes = sourceMeta.getIndexes();
        List<DatabaseMetadataReader.IndexMetadata> targetIndexes = targetMeta.getIndexes();

        // 按索引名分组
        Map<String, List<DatabaseMetadataReader.IndexMetadata>> sourceIndexMap = new LinkedHashMap<>();
        for (DatabaseMetadataReader.IndexMetadata idx : sourceIndexes) {
            sourceIndexMap.computeIfAbsent(idx.getIndexName().toLowerCase(), k -> new ArrayList<>()).add(idx);
        }
        Map<String, List<DatabaseMetadataReader.IndexMetadata>> targetIndexMap = new LinkedHashMap<>();
        for (DatabaseMetadataReader.IndexMetadata idx : targetIndexes) {
            targetIndexMap.computeIfAbsent(idx.getIndexName().toLowerCase(), k -> new ArrayList<>()).add(idx);
        }

        // 索引名称集合
        Set<String> commonIndexes = new HashSet<>(sourceIndexMap.keySet());
        commonIndexes.retainAll(targetIndexMap.keySet());
        Set<String> onlyInSource = new HashSet<>(sourceIndexMap.keySet());
        onlyInSource.removeAll(targetIndexMap.keySet());
        Set<String> onlyInTarget = new HashSet<>(targetIndexMap.keySet());
        onlyInTarget.removeAll(sourceIndexMap.keySet());

        List<String> diffDetails = new ArrayList<>();
        // 仅在源库存在的索引
        for (String idx : onlyInSource) {
            diffDetails.add("仅在源库: " + idx);
        }
        // 仅在目标库存在的索引
        for (String idx : onlyInTarget) {
            diffDetails.add("仅在目标库: " + idx);
        }
        // 共有的索引比对列
        for (String idx : commonIndexes) {
            List<String> sourceCols = sourceIndexMap.get(idx).stream()
                    .map(i -> i.getColumnName().toLowerCase()).sorted().collect(Collectors.toList());
            List<String> targetCols = targetIndexMap.get(idx).stream()
                    .map(i -> i.getColumnName().toLowerCase()).sorted().collect(Collectors.toList());
            if (!sourceCols.equals(targetCols)) {
                diffDetails.add("索引 " + idx + " 列不一致: 源=" + sourceCols + ", 目标=" + targetCols);
            }
            // 比对唯一性
            Boolean sourceUnique = sourceIndexMap.get(idx).get(0).isUnique();
            Boolean targetUnique = targetIndexMap.get(idx).get(0).isUnique();
            if (!Objects.equals(sourceUnique, targetUnique)) {
                diffDetails.add("索引 " + idx + " 唯一性不一致: 源=" + sourceUnique + ", 目标=" + targetUnique);
            }
        }

        boolean consistent = diffDetails.isEmpty();
        result.put("consistent", consistent);
        result.put("sourceCount", sourceIndexes.size());
        result.put("targetCount", targetIndexes.size());
        result.put("onlyInSourceCount", onlyInSource.size());
        result.put("onlyInTargetCount", onlyInTarget.size());
        if (!consistent) {
            result.put("diffs", diffDetails);
        }
        return result;
    }
    
    /**
     * 比对列信息（按列名匹配，不依赖列顺序）
     */
    private static boolean compareColumns(List<DatabaseMetadataReader.ColumnMetadata> sourceCols,
                                           List<DatabaseMetadataReader.ColumnMetadata> targetCols) {
        Map<String, DatabaseMetadataReader.ColumnMetadata> targetMap = new HashMap<>();
        for (DatabaseMetadataReader.ColumnMetadata col : targetCols) {
            targetMap.put(col.getColumnName().toLowerCase(), col);
        }

        if (sourceCols.size() != targetCols.size()) {
            return false;
        }

        for (DatabaseMetadataReader.ColumnMetadata sourceCol : sourceCols) {
            DatabaseMetadataReader.ColumnMetadata targetCol =
                targetMap.get(sourceCol.getColumnName().toLowerCase());
            if (targetCol == null) {
                return false;
            }
            if (!sourceCol.getDataType().equalsIgnoreCase(targetCol.getDataType()) ||
                sourceCol.getColumnSize() != targetCol.getColumnSize() ||
                sourceCol.isNullable() != targetCol.isNullable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two non-null values, handling BigDecimal scale differences.
     */
    private static boolean valuesEqual(Object val1, Object val2) {
        if (val1 instanceof java.math.BigDecimal && val2 instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) val1).compareTo((java.math.BigDecimal) val2) == 0;
        }
        return val1.equals(val2);
    }

    /**
     * 获取主键列列表
     */
    public static List<String> getPrimaryKeys(Connection conn, String tableName, DatabaseType dbType)
            throws SQLException {
        DatabaseMetadataReader.TableMetadata meta =
            DatabaseMetadataReader.readTableMetadata(conn, tableName, dbType);
        return meta.getPrimaryKeys();
    }
    
    /**
     * 比对表数据内容（支持复合主键），返回差异详情
     */
    public static Map<String, Object> compareTableDataWithDetails(Connection sourceConn, Connection targetConn,
                                            String tableName, String targetTableName, List<String> primaryKeys,
                                            DatabaseType sourceDbType, DatabaseType targetDbType,
                                            int batchSize, int maxDiffRows) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();

        if (primaryKeys == null || primaryKeys.isEmpty()) {
            result.put("consistent", false);
            result.put("reason", "无主键");
            return result;
        }

        long sourceCount = countTable(sourceConn, tableName, sourceDbType);
        long targetCount = countTable(targetConn, targetTableName, targetDbType);

        result.put("sourceCount", sourceCount);
        result.put("targetCount", targetCount);

        if (sourceCount != targetCount) {
            // 条数不一致时按主键归并找出具体差异行（仅源有 / 仅目标有）
            Map<String, Object> pkDiff = findPrimaryKeyDiffDetails(
                    sourceConn, targetConn, tableName, targetTableName, primaryKeys,
                    sourceDbType, targetDbType, batchSize, maxDiffRows);
            result.putAll(pkDiff);
            result.put("consistent", false);
            result.put("reason", "数据量不一致(源:" + sourceCount + ", 目标:" + targetCount + ")");
            result.put("sourceCount", sourceCount);
            result.put("targetCount", targetCount);
            return result;
        }

        String orderBy = quoteOrderBy(primaryKeys, sourceDbType);
        long totalScanned = 0;
        long diffCount = 0;
        long missingCount = 0;
        List<Map<String, Object>> diffRows = new ArrayList<>();

        long offset = 0;
        while (offset < sourceCount) {
            List<Map<String, Object>> sourceBatch = readBatchData(
                sourceConn, tableName, orderBy, offset, batchSize, sourceDbType);

            // 将一批源行对应的目标行通过一次批量 IN 查询取回，避免逐行 N+1 查询
            Map<String, Map<String, Object>> targetMap = readTargetRowsByKeys(
                targetConn, targetTableName, primaryKeys, sourceBatch, targetDbType);

            for (Map<String, Object> sourceRow : sourceBatch) {
                totalScanned++;
                Map<String, Object> targetRow = targetMap.get(buildPkKey(primaryKeys, sourceRow));

                if (targetRow == null) {
                    missingCount++;
                    diffCount++;
                    if (diffRows.size() < maxDiffRows) {
                        Map<String, Object> diffEntry = new LinkedHashMap<>();
                        diffEntry.put("type", "missing");
                        diffEntry.put("pk", buildPkValues(primaryKeys, sourceRow));
                        diffEntry.put("sourceValues", sourceRow);
                        diffRows.add(diffEntry);
                    }
                } else if (!compareRows(sourceRow, targetRow)) {
                    diffCount++;
                    if (diffRows.size() < maxDiffRows) {
                        Map<String, Object> diffEntry = new LinkedHashMap<>();
                        diffEntry.put("type", "mismatch");
                        diffEntry.put("pk", buildPkValues(primaryKeys, sourceRow));
                        diffEntry.put("diffFields", collectDiffFields(sourceRow, targetRow));
                        diffRows.add(diffEntry);
                    }
                }
            }
            offset += batchSize;
            // 提前退出：差异行过多或扫描行数足够
            if (diffCount > maxDiffRows * 10 && diffRows.size() >= maxDiffRows) {
                break;
            }
        }

        result.put("totalScanned", totalScanned);
        result.put("diffCount", diffCount);
        result.put("missingCount", missingCount);
        result.put("mismatchCount", diffCount - missingCount);
        result.put("consistent", diffCount == 0);
        result.put("diffRows", diffRows);
        result.put("primaryKeys", primaryKeys);
        result.put("maxDiffRows", maxDiffRows);

        if (diffCount == 0) {
            log.info("表 {} 的数据内容一致 (扫描{}行)", tableName, totalScanned);
        } else {
            log.warn("表 {} 发现{}处差异 (扫描{}行，含{}缺失)", tableName, diffCount, totalScanned, missingCount);
        }

        return result;
    }

    /**
     * 按主键有序归并，找出仅源库有 / 仅目标库有的行（用于条数差异下钻）。
     * 两侧各最多收集 {@code maxDiffRows} 条；达到上限后提前结束扫描。
     */
    public static Map<String, Object> findPrimaryKeyDiffDetails(
            Connection sourceConn, Connection targetConn,
            String tableName, String targetTableName, List<String> primaryKeys,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            int batchSize, int maxDiffRows) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            result.put("consistent", false);
            result.put("reason", "无主键，无法定位差异行");
            result.put("diffRows", Collections.emptyList());
            return result;
        }
        if (batchSize < 1) {
            batchSize = 1000;
        }
        if (maxDiffRows < 0) {
            maxDiffRows = 0;
        }

        long sourceCount = countTable(sourceConn, tableName, sourceDbType);
        long targetCount = countTable(targetConn, targetTableName, targetDbType);
        result.put("sourceCount", sourceCount);
        result.put("targetCount", targetCount);
        result.put("diff", sourceCount - targetCount);
        result.put("primaryKeys", primaryKeys);
        result.put("maxDiffRows", maxDiffRows);

        String srcOrderBy = quoteOrderBy(primaryKeys, sourceDbType);
        String tgtOrderBy = quoteOrderBy(primaryKeys, targetDbType);

        List<Map<String, Object>> onlyInSource = new ArrayList<>();
        List<Map<String, Object>> onlyInTarget = new ArrayList<>();
        List<Map<String, Object>> diffRows = new ArrayList<>();

        List<Map<String, Object>> srcBatch = Collections.emptyList();
        List<Map<String, Object>> tgtBatch = Collections.emptyList();
        int si = 0;
        int ti = 0;
        long srcOffset = 0;
        long tgtOffset = 0;
        boolean srcDone = sourceCount <= 0;
        boolean tgtDone = targetCount <= 0;
        long scannedSource = 0;
        long scannedTarget = 0;
        boolean truncated = false;

        while (!srcDone || !tgtDone) {
            if (!srcDone && si >= srcBatch.size()) {
                srcBatch = readBatchData(sourceConn, tableName, srcOrderBy, srcOffset, batchSize, sourceDbType);
                srcOffset += batchSize;
                si = 0;
                if (srcBatch.isEmpty()) {
                    srcDone = true;
                }
            }
            if (!tgtDone && ti >= tgtBatch.size()) {
                tgtBatch = readBatchData(targetConn, targetTableName, tgtOrderBy, tgtOffset, batchSize, targetDbType);
                tgtOffset += batchSize;
                ti = 0;
                if (tgtBatch.isEmpty()) {
                    tgtDone = true;
                }
            }

            if (srcDone && tgtDone) {
                break;
            }

            boolean sourceFull = onlyInSource.size() >= maxDiffRows;
            boolean targetFull = onlyInTarget.size() >= maxDiffRows;
            if (sourceFull && targetFull) {
                truncated = true;
                break;
            }

            if (srcDone) {
                if (!targetFull) {
                    Map<String, Object> tgtRow = tgtBatch.get(ti);
                    scannedTarget++;
                    Map<String, Object> entry = buildSideDiffEntry("only_in_target", primaryKeys, tgtRow);
                    onlyInTarget.add(entry);
                    if (diffRows.size() < maxDiffRows * 2) {
                        diffRows.add(entry);
                    }
                } else {
                    truncated = true;
                }
                ti++;
                continue;
            }
            if (tgtDone) {
                if (!sourceFull) {
                    Map<String, Object> srcRow = srcBatch.get(si);
                    scannedSource++;
                    Map<String, Object> entry = buildSideDiffEntry("only_in_source", primaryKeys, srcRow);
                    onlyInSource.add(entry);
                    if (diffRows.size() < maxDiffRows * 2) {
                        diffRows.add(entry);
                    }
                } else {
                    truncated = true;
                }
                si++;
                continue;
            }

            Map<String, Object> srcRow = srcBatch.get(si);
            Map<String, Object> tgtRow = tgtBatch.get(ti);
            int cmp = comparePkMaps(primaryKeys, srcRow, tgtRow);
            if (cmp < 0) {
                scannedSource++;
                if (!sourceFull) {
                    Map<String, Object> entry = buildSideDiffEntry("only_in_source", primaryKeys, srcRow);
                    onlyInSource.add(entry);
                    if (diffRows.size() < maxDiffRows * 2) {
                        diffRows.add(entry);
                    }
                } else {
                    truncated = true;
                }
                si++;
            } else if (cmp > 0) {
                scannedTarget++;
                if (!targetFull) {
                    Map<String, Object> entry = buildSideDiffEntry("only_in_target", primaryKeys, tgtRow);
                    onlyInTarget.add(entry);
                    if (diffRows.size() < maxDiffRows * 2) {
                        diffRows.add(entry);
                    }
                } else {
                    truncated = true;
                }
                ti++;
            } else {
                scannedSource++;
                scannedTarget++;
                si++;
                ti++;
            }
        }

        long onlySrcCount = onlyInSource.size();
        long onlyTgtCount = onlyInTarget.size();
        // 若提前截断，已收集数可能小于真实差异；用条数差作下界提示
        result.put("onlyInSourceCount", onlySrcCount);
        result.put("onlyInTargetCount", onlyTgtCount);
        result.put("onlyInSource", onlyInSource);
        result.put("onlyInTarget", onlyInTarget);
        result.put("diffRows", diffRows);
        result.put("diffCount", onlySrcCount + onlyTgtCount);
        result.put("missingCount", onlySrcCount);
        result.put("extraCount", onlyTgtCount);
        result.put("mismatchCount", 0);
        result.put("scannedSource", scannedSource);
        result.put("scannedTarget", scannedTarget);
        result.put("truncated", truncated);
        result.put("consistent", onlySrcCount == 0 && onlyTgtCount == 0 && sourceCount == targetCount);

        log.info("表 {} 主键差异: 仅源={} 仅目标={} truncated={} (源条数={}, 目标条数={})",
                tableName, onlySrcCount, onlyTgtCount, truncated, sourceCount, targetCount);
        return result;
    }

    private static Map<String, Object> buildSideDiffEntry(String type, List<String> primaryKeys, Map<String, Object> row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("pk", buildPkValues(primaryKeys, row));
        if ("only_in_source".equals(type) || "missing".equals(type)) {
            entry.put("sourceValues", row);
        } else {
            entry.put("targetValues", row);
        }
        return entry;
    }

    private static String quoteOrderBy(List<String> primaryKeys, DatabaseType dbType) {
        return SyncDataSyncHelper.buildOrderBy(primaryKeys, col -> SqlHelper.quoteIdentifier(col, dbType));
    }

    private static int comparePkMaps(List<String> primaryKeys, Map<String, Object> left, Map<String, Object> right) {
        for (String pk : primaryKeys) {
            int c = SyncDataSyncHelper.compareValues(getRowValue(left, pk), getRowValue(right, pk));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static Object getRowValue(Map<String, Object> row, String col) {
        if (row == null || col == null) {
            return null;
        }
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

    /**
     * 构建主键值映射
     */
    private static Map<String, Object> buildPkValues(List<String> primaryKeys, Map<String, Object> row) {
        Map<String, Object> pkValues = new LinkedHashMap<>();
        for (String pk : primaryKeys) {
            pkValues.put(pk, getRowValue(row, pk));
        }
        return pkValues;
    }

    /**
     * 收集两行数据中不一致的字段
     */
    private static Map<String, Map<String, Object>> collectDiffFields(Map<String, Object> sourceRow, 
                                                                       Map<String, Object> targetRow) {
        Map<String, Map<String, Object>> diffs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sourceRow.entrySet()) {
            String col = entry.getKey();
            Object srcVal = entry.getValue();
            Object tgtVal = targetRow.get(col);

            if (srcVal == null && tgtVal == null) continue;
            if (srcVal == null || tgtVal == null || !valuesEqual(srcVal, tgtVal)) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("source", srcVal);
                pair.put("target", tgtVal);
                diffs.put(col, pair);
            }
        }
        return diffs;
    }

    /**
     * 比对表数据内容（支持复合主键），仅返回一致状态（向后兼容）
     */
    public static boolean compareTableData(Connection sourceConn, Connection targetConn,
                                            String tableName, String targetTableName, List<String> primaryKeys,
                                            DatabaseType sourceDbType, DatabaseType targetDbType,
                                            int batchSize) throws SQLException {
        Map<String, Object> result = compareTableDataWithDetails(
            sourceConn, targetConn, tableName, targetTableName, primaryKeys, sourceDbType, targetDbType, batchSize, 0);
        return Boolean.TRUE.equals(result.get("consistent"));
    }
    
    /**
     * 读取批次数据
     */
    private static List<Map<String, Object>> readBatchData(Connection conn, String tableName,
                                                            String orderBy, long offset,
                                                            int batchSize, DatabaseType dbType) throws SQLException {
        List<Map<String, Object>> batch = new java.util.ArrayList<>();
        String sql = DatabaseDialect.buildPaginationSql(tableName, orderBy, offset, batchSize, dbType);
        
        try (java.sql.Statement stmt = conn.createStatement(); java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            while (rs.next()) {
                Map<String, Object> row = new java.util.HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                batch.add(row);
            }
        }
        
        return batch;
    }
    
    /**
     * 按主键读取目标库单行（PreparedStatement）
     */
    public static Map<String, Object> readRowByKeys(Connection conn, String tableName,
                                                     List<String> primaryKeys, Map<String, Object> row,
                                                     DatabaseType dbType) throws SQLException {
        SqlHelper.WhereClause where = SqlHelper.buildWhereClause(primaryKeys, row, dbType);
        String sql = "SELECT * FROM " + tableName + " WHERE " + where.getSql();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            SqlHelper.bindParams(pstmt, where, 1);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    java.sql.ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        result.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 根据主键构建行的唯一标识 key（用于批量结果内存匹配）
     */
    private static String buildPkKey(List<String> primaryKeys, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) sb.append('\u0001');
            Object v = getRowValue(row, primaryKeys.get(i));
            sb.append(v == null ? "NULL" : v.toString());
        }
        return sb.toString();
    }

    /**
     * 批量按主键读取目标行，一次查询取回整批，避免逐行 N+1 查询。
     * 返回以主键 key 为索引的目标行 Map。
     */
    private static Map<String, Map<String, Object>> readTargetRowsByKeys(
            Connection conn, String tableName, List<String> primaryKeys,
            List<Map<String, Object>> sourceBatch, DatabaseType dbType) throws SQLException {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (sourceBatch.isEmpty()) {
            return result;
        }

        // 构建 (k1=? AND k2=?) OR (...) 形式的 WHERE 条件
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < sourceBatch.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append("(");
            for (int j = 0; j < primaryKeys.size(); j++) {
                if (j > 0) where.append(" AND ");
                where.append(SqlHelper.quoteIdentifier(primaryKeys.get(j), dbType)).append(" = ?");
            }
            where.append(")");
        }
        String sql = "SELECT * FROM " + tableName + " WHERE " + where;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Map<String, Object> sourceRow : sourceBatch) {
                for (String pk : primaryKeys) {
                    pstmt.setObject(idx++, sourceRow.get(pk));
                }
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                java.sql.ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    result.put(buildPkKey(primaryKeys, row), row);
                }
            }
        }
        return result;
    }

    /**
     * 比对两行数据
     */
    public static boolean compareRows(Map<String, Object> row1, Map<String, Object> row2) {
        if (row1.size() != row2.size()) {
            return false;
        }
        
        for (Map.Entry<String, Object> entry : row1.entrySet()) {
            Object val1 = entry.getValue();
            Object val2 = row2.get(entry.getKey());
            
            if (val1 == null && val2 == null) {
                continue;
            }
            if (val1 == null || val2 == null) {
                return false;
            }
            if (!valuesEqual(val1, val2)) {
                return false;
            }
        }
        
        return true;
    }
}
