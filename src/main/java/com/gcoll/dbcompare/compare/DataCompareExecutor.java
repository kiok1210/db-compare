package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.dictionary.FieldClassifier;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.sql.SqlHelper;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按比对模式执行数据比对：抽样 / 增量
 */
@Slf4j
public class DataCompareExecutor {

    private static final int DEFAULT_INCREMENTAL_HOURS = 24;

    /**
     * 抽样比对
     */
    public static Map<String, Object> compareSample(
            Connection sourceConn, Connection targetConn,
            String tableName, String targetTableName, DatabaseMetadataReader.TableMetadata tableMeta,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            double sampleRate) throws Exception {

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> primaryKeys = tableMeta.getPrimaryKeys();

        if (primaryKeys.isEmpty()) {
            result.put("sample", sampleSkipped("无主键"));
            result.put("consistent", true);
            return result;
        }

        long totalCount = countTable(sourceConn, tableName);
        int sampleSize = Math.max(1, (int) (totalCount * sampleRate));

        String sql = DatabaseMetadataReader.buildSampleSql(tableName, sampleRate, sourceDbType, sampleSize);
        long matched = 0;
        long mismatched = 0;
        long missing = 0;

        try (PreparedStatement pstmt = sourceConn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> sourceRow = readRow(rs, metaData, columnCount);
                Map<String, Object> targetRow = DatabaseCompareAdapter.readRowByKeys(
                    targetConn, targetTableName, primaryKeys, sourceRow, targetDbType);

                if (targetRow == null) {
                    missing++;
                } else if (!DatabaseCompareAdapter.compareRows(sourceRow, targetRow)) {
                    mismatched++;
                } else {
                    matched++;
                }
            }
        }

        Map<String, Object> sampleInfo = new LinkedHashMap<>();
        sampleInfo.put("totalRows", totalCount);
        sampleInfo.put("sampleSize", sampleSize);
        sampleInfo.put("sampleRate", sampleRate);
        sampleInfo.put("matched", matched);
        sampleInfo.put("mismatched", mismatched);
        sampleInfo.put("missing", missing);
        sampleInfo.put("consistent", mismatched == 0 && missing == 0);
        result.put("sample", sampleInfo);

        long targetCount = countTable(targetConn, targetTableName);
        Map<String, Object> countInfo = new LinkedHashMap<>();
        countInfo.put("sourceCount", totalCount);
        countInfo.put("targetCount", targetCount);
        countInfo.put("diff", totalCount - targetCount);
        countInfo.put("consistent", totalCount == targetCount);
        result.put("count", countInfo);
        result.put("consistent", (Boolean) sampleInfo.get("consistent") && (Boolean) countInfo.get("consistent"));

        log.info("表 {} 抽样比对: 抽样={}, 一致={}, 不一致={}, 缺失={}",
            tableName, sampleSize, matched, mismatched, missing);

        return result;
    }

    /**
     * 增量比对（基于时间字段）
     */
    public static Map<String, Object> compareIncremental(
            Connection sourceConn, Connection targetConn,
            String tableName, String targetTableName, DatabaseMetadataReader.TableMetadata tableMeta,
            DatabaseType sourceDbType, DatabaseType targetDbType) throws Exception {

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> primaryKeys = tableMeta.getPrimaryKeys();

        if (primaryKeys.isEmpty()) {
            result.put("incremental", sampleSkipped("无主键"));
            result.put("consistent", true);
            return result;
        }

        FieldClassifier.FieldClassification classification = FieldClassifier.classify(tableMeta.getColumns());
        String timeField = resolveTimeField(classification.getTimeFieldNames());
        if (timeField == null) {
            result.put("incremental", sampleSkipped("未找到时间字段(update_time/create_time等)"));
            result.put("consistent", true);
            return result;
        }

        Timestamp since = new Timestamp(System.currentTimeMillis() - DEFAULT_INCREMENTAL_HOURS * 3600_000L);
        String sql = "SELECT * FROM " + SqlHelper.quoteIdentifier(tableName, sourceDbType)
            + " WHERE " + SqlHelper.quoteIdentifier(timeField, sourceDbType) + " >= ?";

        long matched = 0;
        long mismatched = 0;
        long missing = 0;
        long scanned = 0;

        try (PreparedStatement pstmt = sourceConn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, since);
            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    scanned++;
                    Map<String, Object> sourceRow = readRow(rs, metaData, columnCount);
                    Map<String, Object> targetRow = DatabaseCompareAdapter.readRowByKeys(
                        targetConn, targetTableName, primaryKeys, sourceRow, targetDbType);

                    if (targetRow == null) {
                        missing++;
                    } else if (!DatabaseCompareAdapter.compareRows(sourceRow, targetRow)) {
                        mismatched++;
                    } else {
                        matched++;
                    }
                }
            }
        }

        Map<String, Object> incInfo = new LinkedHashMap<>();
        incInfo.put("timeField", timeField);
        incInfo.put("since", since.toString());
        incInfo.put("scannedRows", scanned);
        incInfo.put("matched", matched);
        incInfo.put("mismatched", mismatched);
        incInfo.put("missing", missing);
        incInfo.put("consistent", mismatched == 0 && missing == 0);
        result.put("incremental", incInfo);
        result.put("consistent", incInfo.get("consistent"));

        log.info("表 {} 增量比对: 字段={}, 扫描={}, 一致={}, 不一致={}, 缺失={}",
            tableName, timeField, scanned, matched, mismatched, missing);

        return result;
    }

    private static String resolveTimeField(List<String> timeFields) {
        if (timeFields == null || timeFields.isEmpty()) {
            return null;
        }
        for (String preferred : new String[]{"update_time", "modify_time", "updated_at", "modified_at", "create_time"}) {
            for (String field : timeFields) {
                if (field.equalsIgnoreCase(preferred)) {
                    return field;
                }
            }
        }
        return timeFields.get(0);
    }

    private static Map<String, Object> sampleSkipped(String reason) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("skipped", true);
        info.put("reason", reason);
        return info;
    }

    private static long countTable(Connection conn, String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static Map<String, Object> readRow(ResultSet rs, ResultSetMetaData metaData, int columnCount)
            throws Exception {
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= columnCount; i++) {
            row.put(metaData.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
