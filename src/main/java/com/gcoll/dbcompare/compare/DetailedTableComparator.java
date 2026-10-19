package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.dictionary.FieldClassifier;
import com.gcoll.dbcompare.entity.DatabaseType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指定表详细比对：字段画像（主键/索引/状态/业务日期/金额）+ 差异行定位 + 源/目标 SQL。
 */
public final class DetailedTableComparator {

    private DetailedTableComparator() {
    }

    /**
     * @param pureTableName 裸表名（用于读元数据）
     * @param srcCatalog    源 catalog/schema，可空
     * @param tgtCatalog    目标 catalog/schema，可空
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compareDetailed(
            Connection sourceConn, Connection targetConn,
            String pureTableName, String srcCatalog, String tgtCatalog,
            String srcQualified, String tgtQualified,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            int batchSize, int maxDiffRows) throws Exception {

        DatabaseMetadataReader.TableMetadata meta;
        if (srcCatalog != null && !srcCatalog.isEmpty()) {
            meta = DatabaseMetadataReader.readTableMetadata(sourceConn, pureTableName, sourceDbType, srcCatalog);
        } else {
            meta = DatabaseMetadataReader.readTableMetadata(sourceConn, pureTableName, sourceDbType);
        }

        List<String> primaryKeys = meta.getPrimaryKeys() != null
                ? meta.getPrimaryKeys() : Collections.<String>emptyList();
        FieldClassifier.FieldClassification classification = FieldClassifier.classify(meta.getColumns());
        List<String> indexColumns = FieldClassifier.extractIndexColumnNames(meta.getIndexes(), primaryKeys);
        List<String> focusColumns = FieldClassifier.buildFocusColumns(
                primaryKeys, indexColumns, classification);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("primaryKeys", primaryKeys);
        profile.put("indexColumns", indexColumns);
        profile.put("statusFields", classification.getStatusFieldNames());
        profile.put("businessDateFields", classification.getBusinessDateFieldNames());
        profile.put("amountFields", classification.getAmountFieldNames());
        profile.put("timeFields", classification.getTimeFieldNames());
        profile.put("focusColumns", focusColumns);
        profile.put("indexes", summarizeIndexes(meta.getIndexes()));

        Map<String, Object> detail = DatabaseCompareAdapter.findPrimaryKeyDiffDetails(
                sourceConn, targetConn, srcQualified, tgtQualified, primaryKeys,
                sourceDbType, targetDbType, batchSize, maxDiffRows, focusColumns);

        List<Map<String, Object>> diffRows = (List<Map<String, Object>>) detail.get("diffRows");
        if (diffRows == null) {
            diffRows = Collections.emptyList();
        }
        // SELECT：主键在前 + 实际差异字段（不含其它关注列）
        List<String> diffFieldNames = collectDiffFieldNames(diffRows);
        List<String> selectCols = DiffLocateSqlBuilder.mergeSelectColumns(primaryKeys, diffFieldNames);
        List<Map<String, Object>> pkRows = collectPkRows(diffRows);
        Map<String, Object> locateSql = DiffLocateSqlBuilder.buildBatchedLocateSql(
                srcQualified, tgtQualified, selectCols, primaryKeys, pkRows,
                sourceDbType, targetDbType, DiffLocateSqlBuilder.DEFAULT_IN_BATCH_SIZE);
        locateSql.put("selectColumns", selectCols);
        locateSql.put("diffFields", diffFieldNames);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detailed", true);
        out.put("fieldProfile", profile);
        out.put("sourceTable", srcQualified);
        out.put("targetTable", tgtQualified);
        out.put("sourceCatalog", srcCatalog);
        out.put("targetCatalog", tgtCatalog);
        out.putAll(detail);
        out.put("locateSql", locateSql);
        // 兼容旧前端：顶层也提供合并后的源/目标 SQL（主键 IN，分段）
        out.put("sourceSql", locateSql.get("sourceSql"));
        out.put("targetSql", locateSql.get("targetSql"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> collectPkRows(List<Map<String, Object>> diffRows) {
        if (diffRows == null || diffRows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> pks = new ArrayList<>(diffRows.size());
        for (Map<String, Object> row : diffRows) {
            if (row == null) {
                continue;
            }
            Object pkObj = row.get("pk");
            if (pkObj instanceof Map) {
                pks.add((Map<String, Object>) pkObj);
            }
        }
        return pks;
    }

    /** 从差异行收集实际不一致字段名（去重保序）；仅源/仅目标无 diffFields 时不追加。 */
    @SuppressWarnings("unchecked")
    static List<String> collectDiffFieldNames(List<Map<String, Object>> diffRows) {
        if (diffRows == null || diffRows.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Boolean> names = new LinkedHashMap<>();
        for (Map<String, Object> row : diffRows) {
            if (row == null) {
                continue;
            }
            Object df = row.get("diffFields");
            if (!(df instanceof Map)) {
                continue;
            }
            for (Object key : ((Map<?, ?>) df).keySet()) {
                if (key != null) {
                    String name = String.valueOf(key).trim();
                    if (!name.isEmpty()) {
                        names.put(name, Boolean.TRUE);
                    }
                }
            }
        }
        return new ArrayList<>(names.keySet());
    }

    private static List<Map<String, Object>> summarizeIndexes(
            List<DatabaseMetadataReader.IndexMetadata> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (DatabaseMetadataReader.IndexMetadata idx : indexes) {
            if (idx == null || idx.getIndexName() == null) {
                continue;
            }
            String name = idx.getIndexName();
            Map<String, Object> item = byName.get(name);
            if (item == null) {
                item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("unique", idx.isUnique());
                item.put("columns", new ArrayList<String>());
                byName.put(name, item);
            }
            if (idx.getColumnName() != null) {
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) item.get("columns");
                cols.add(idx.getColumnName());
            }
        }
        return new ArrayList<>(byName.values());
    }
}
