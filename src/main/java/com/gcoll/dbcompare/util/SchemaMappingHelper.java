package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.entity.DatabaseType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库名映射与表 catalog 解析（比对/同步共用）
 * 库对库：源库 catalog → 目标库 catalog（用户映射 &gt; 单选目标库 &gt; 多库推断 &gt; 同名库）
 * 表对标：映射后的目标库中使用同名表
 */
public final class SchemaMappingHelper {

    private static final String[] TARGET_PREFIXES = {"prd_", "prod_", "pro_"};

    private SchemaMappingHelper() {
    }

    public static Map<String, String> parseSchemaMapping(String mapping) {
        Map<String, String> map = new LinkedHashMap<>();
        if (mapping != null && !mapping.trim().isEmpty()) {
            for (String pair : mapping.split(",")) {
                String[] parts = pair.split(":", 2);
                if (parts.length == 2) {
                    String src = parts[0].trim();
                    String tgt = parts[1].trim();
                    if (!src.isEmpty() && !tgt.isEmpty()) {
                        map.put(src, tgt);
                    }
                }
            }
        }
        return map;
    }

    /** 将映射 Map 格式化为 a:b,c:d */
    public static String formatSchemaMapping(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 按源库列表补全/收窄映射：已存映射优先，否则在目标库列表中推断（如 gcoll_acs → prd_gcoll_acs）。
     */
    public static String buildAutoSchemaMapping(String sourceSchemaCsv, String targetSchemaCsv, String existingMapping) {
        Map<String, String> existing = parseSchemaMapping(existingMapping);
        List<String> sources = splitCsv(sourceSchemaCsv);
        if (sources.isEmpty()) {
            return formatSchemaMapping(existing);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String src : sources) {
            String tgt = resolveTargetCatalog(src, existing, targetSchemaCsv);
            if (tgt != null && !tgt.isEmpty()) {
                out.put(src, tgt);
            }
        }
        return formatSchemaMapping(out);
    }

    /**
     * 从映射字符串提取目标库 CSV（去重保序）。
     */
    public static String targetSchemasFromMapping(String mapping) {
        Map<String, String> map = parseSchemaMapping(mapping);
        if (map.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (String tgt : map.values()) {
            if (tgt != null && !tgt.isEmpty()) {
                seen.put(tgt, Boolean.TRUE);
            }
        }
        return seen.isEmpty() ? null : String.join(",", seen.keySet());
    }

    /**
     * 目标多库模式下，建立纯表名 → 目标 catalog 映射（仅业务库，不含系统库）
     */
    public static Map<String, String> buildTargetTableCatalogMap(
            Connection conn, String targetSchema, DatabaseType dbType) throws Exception {
        Map<String, String> tgtTableCatalogMap = new LinkedHashMap<>();
        List<String> targetRawTables = resolveRawTables(conn, targetSchema, dbType);
        for (String t : targetRawTables) {
            if (t.contains("::")) {
                String[] parts = t.split("::", 2);
                if (DatabaseMetadataReader.isSystemCatalog(parts[0])) {
                    continue;
                }
                tgtTableCatalogMap.putIfAbsent(parts[1].toLowerCase(), parts[0]);
            }
        }
        return tgtTableCatalogMap;
    }

    public static List<String> resolveRawTables(Connection conn, String schema, DatabaseType dbType) throws Exception {
        if (schema == null || schema.trim().isEmpty()) {
            return DatabaseMetadataReader.readAllTablesFromAllSchemas(conn, dbType);
        }
        if (schema.contains(",")) {
            List<String> allTables = new ArrayList<>();
            for (String s : schema.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty() && !DatabaseMetadataReader.isSystemCatalog(trimmed)) {
                    for (String t : DatabaseMetadataReader.readAllTables(conn, trimmed, dbType)) {
                        allTables.add(trimmed + "::" + t);
                    }
                }
            }
            return allTables;
        }
        return DatabaseMetadataReader.readAllTables(conn, schema, dbType);
    }

    /**
     * 解析单张表的源/目标 catalog 与纯表名
     */
    public static CatalogPair resolveCatalogs(
            String tableName,
            String sourceSchema,
            String targetSchema,
            boolean isMultiSource,
            boolean isMultiTarget,
            Map<String, String> schemaMap,
            Map<String, String> tgtTableCatalogMap) {

        String srcCat = null;
        String tgtCat = null;
        String pureTableName = tableName;
        boolean qualified = tableName.contains("::");

        if (qualified) {
            String[] parts = tableName.split("::", 2);
            String prefixSchema = parts[0];
            pureTableName = parts[1];
            if (isMultiSource || (sourceSchema != null && sourceSchema.contains(","))) {
                srcCat = prefixSchema;
            }
            // 库对库：有 schema::table 前缀时，只按库映射解析，不按表名猜测目标库
            tgtCat = resolveTargetCatalog(prefixSchema, schemaMap, targetSchema);
        } else if (isMultiTarget && tgtTableCatalogMap != null && !tgtTableCatalogMap.isEmpty()) {
            // 源单库 + 目标多库且表名无前缀：按同名表定位目标库
            tgtCat = tgtTableCatalogMap.get(pureTableName.toLowerCase());
        }

        return new CatalogPair(srcCat, tgtCat, pureTableName);
    }

    /**
     * 目标库 catalog：用户映射 &gt; 目标单选库 &gt; 多目标列表推断 &gt; 同名库
     */
    public static String resolveTargetCatalog(String prefixSchema, Map<String, String> schemaMap, String targetSchema) {
        if (schemaMap != null) {
            String mapped = schemaMap.get(prefixSchema);
            if (mapped != null && !mapped.isEmpty()) {
                return mapped;
            }
        }
        if (targetSchema != null && !targetSchema.trim().isEmpty() && !targetSchema.contains(",")) {
            return targetSchema.trim();
        }
        String inferred = suggestTargetSchema(prefixSchema, splitCsv(targetSchema), schemaMap);
        if (inferred != null && !inferred.isEmpty()) {
            return inferred;
        }
        return prefixSchema;
    }

    /**
     * 与前端 SchemaMappingUi.suggestTargetSchema 对齐：
     * 已存映射 &gt; prd_/prod_ 前缀 &gt; 后缀/分段匹配 &gt; 同名。
     */
    public static String suggestTargetSchema(String srcName, List<String> tgtList, Map<String, String> savedMap) {
        if (srcName == null || srcName.isEmpty()) {
            return null;
        }
        if (savedMap != null) {
            String mapped = savedMap.get(srcName);
            if (mapped != null && !mapped.isEmpty()) {
                return mapped;
            }
        }
        if (tgtList == null || tgtList.isEmpty()) {
            return null;
        }

        for (String prefix : TARGET_PREFIXES) {
            String candidate = prefix + srcName;
            if (containsIgnoreCase(tgtList, candidate)) {
                return findExact(tgtList, candidate);
            }
        }

        String best = null;
        int bestScore = 0;
        for (String tgt : tgtList) {
            if (tgt == null || tgt.isEmpty() || DatabaseMetadataReader.isSystemCatalog(tgt)) {
                continue;
            }
            String[] srcParts = srcName.split("_");
            String[] tgtParts = tgt.split("_");
            int matchLen = 0;
            for (int si = srcParts.length - 1, ti = tgtParts.length - 1; si >= 0 && ti >= 0; si--, ti--) {
                if (srcParts[si].equals(tgtParts[ti])) {
                    matchLen++;
                } else {
                    break;
                }
            }
            if (matchLen > bestScore) {
                bestScore = matchLen;
                best = tgt;
            }
        }
        if (bestScore >= 2) {
            return best;
        }

        for (String tgt : tgtList) {
            if (tgt == null || tgt.isEmpty() || DatabaseMetadataReader.isSystemCatalog(tgt)) {
                continue;
            }
            if (tgt.length() > srcName.length() && tgt.endsWith(srcName)) {
                return tgt;
            }
            if (srcName.length() > tgt.length() && srcName.endsWith(tgt)) {
                return tgt;
            }
        }

        if (containsIgnoreCase(tgtList, srcName)) {
            return findExact(tgtList, srcName);
        }
        return null;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String findExact(List<String> list, String name) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return name;
    }

    public static final class CatalogPair {
        public final String srcCat;
        public final String tgtCat;
        public final String pureTableName;

        public CatalogPair(String srcCat, String tgtCat, String pureTableName) {
            this.srcCat = srcCat;
            this.tgtCat = tgtCat;
            this.pureTableName = pureTableName;
        }
    }
}
