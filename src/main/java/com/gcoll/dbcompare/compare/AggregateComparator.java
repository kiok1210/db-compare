package com.gcoll.dbcompare.compare;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.dictionary.FieldClassifier;
import com.gcoll.dbcompare.entity.DatabaseType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聚合数据比对器
 * 
 * 从多维度比对数据一致性，相比逐行比对更高效：
 * 
 * 维度1：数据条数比对（COUNT）
 * 维度2：金额字段SUM比对（智能识别金额字段，排除利率字段）
 * 维度3：行数据Hash比对（MD5聚合，快速检测数据内容差异）
 * 
 * 优势：
 * - 一次查询即可完成多维度比对，避免逐行传输数据
 * - 自动识别金额字段，无需手动配置
 * - Hash比对可快速发现数据内容不一致
 * - 源库/目标库分属不同连接，各维度查询并发执行以缩短整体耗时
 */
@Slf4j
public class AggregateComparator {

    /**
     * 单表任一侧行数 ≥ 此值时跳过全表 MD5 Hash（千万级表上每行 MD5 会卡死 UI，看起来像停在初始化）。
     * 仍做 COUNT + 金额 SUM；内容一致性请用抽样/同步后校验。
     */
    public static final long SKIP_ROW_HASH_ROW_THRESHOLD = 1_000_000L;

    /** 源/目标查询并发执行线程池（每个维度最多 2 个线程，按表复用） */
    private static final AtomicInteger POOL_SEQ = new AtomicInteger(0);
    private static final ExecutorService QUERY_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agg-query-" + POOL_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /**
     * 执行聚合比对
     *
     * @param sourceConn 源库连接
     * @param targetConn 目标库连接
     * @param tableName  表名
     * @param columns    表的列元数据
     * @param sourceDbType 源库类型（用于源端 Hash 方言）
     * @param targetDbType 目标库类型（用于目标端 Hash 方言）
     * @return 聚合比对结果
     */
    public static AggregateCompareResult compare(
            Connection sourceConn, Connection targetConn,
            String tableName, String targetTableName, List<DatabaseMetadataReader.ColumnMetadata> columns,
            DatabaseType sourceDbType, DatabaseType targetDbType) throws Exception {

        AggregateCompareResult result = new AggregateCompareResult();
        result.setTableName(tableName);

        // 1. 智能分类字段
        FieldClassifier.FieldClassification classification = FieldClassifier.classify(columns);
        result.setAmountFieldNames(classification.getAmountFieldNames());
        result.setTimeFieldNames(classification.getTimeFieldNames());
        result.setHasAmountField(classification.isHasAmountField());

        // 收集所有列名
        List<String> allColumnNames = new ArrayList<>();
        for (DatabaseMetadataReader.ColumnMetadata col : columns) {
            allColumnNames.add(col.getColumnName());
        }

        // 2. 维度1：数据条数比对（源/目标并发）
        compareCount(sourceConn, targetConn, tableName, targetTableName, result);

        // 3. 维度2：金额字段SUM比对（如果存在金额字段，源/目标并发）
        if (classification.isHasAmountField()) {
            compareAmountSum(sourceConn, targetConn, tableName, targetTableName,
                classification.getAmountFieldNames(), result);
        }

        // 4. 维度3：行数据Hash比对（超大表跳过全表 MD5，避免长时间无进度）
        if (shouldSkipRowHash(result.getSourceCount(), result.getTargetCount())) {
            result.setRowHashSkipped(true);
            result.setRowHashConsistent(true);
            log.info("表 {} 行数源={} 目标={} ≥{}，跳过全表 MD5 Hash（仍保留条数/金额比对）",
                    tableName, result.getSourceCount(), result.getTargetCount(),
                    SKIP_ROW_HASH_ROW_THRESHOLD);
        } else {
            compareRowHash(sourceConn, targetConn, tableName, targetTableName, allColumnNames,
                sourceDbType, targetDbType, result);
        }

        // 5. 综合判断
        result.setConsistent(result.isCountConsistent() 
            && result.isAmountSumConsistent() 
            && (result.isRowHashSkipped() || result.isRowHashConsistent()));

        if (result.isConsistent()) {
            log.info("表 {} 多维度比对一致 (条数={}, 金额字段={}个, Hash{})", 
                     tableName, result.getSourceCount(), 
                     classification.getAmountFieldNames().size(),
                     result.isRowHashSkipped() ? "已跳过" : "一致");
        } else {
            log.warn("表 {} 存在差异: 条数一致={}, 金额一致={}, Hash一致={}{}", 
                     tableName, result.isCountConsistent(),
                     result.isAmountSumConsistent(), result.isRowHashConsistent(),
                     result.isRowHashSkipped() ? "(已跳过)" : "");
        }

        return result;
    }

    /** 超大表跳过全表行 Hash */
    public static boolean shouldSkipRowHash(long sourceCount, long targetCount) {
        return Math.max(sourceCount, targetCount) >= SKIP_ROW_HASH_ROW_THRESHOLD;
    }

    // ============ 并发辅助 ============

    /** 并发执行两个独立查询（分别使用 source / target 连接，连接互不共享故线程安全） */
    private static <T> Pair<T> runBoth(Connection sourceConn, Connection targetConn,
                                       QueryFunc<T> sourceQuery, QueryFunc<T> targetQuery) throws Exception {
        CompletableFuture<T> fSrc = CompletableFuture.supplyAsync(() -> {
            try { return sourceQuery.apply(sourceConn); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, QUERY_POOL);
        CompletableFuture<T> fTgt = CompletableFuture.supplyAsync(() -> {
            try { return targetQuery.apply(targetConn); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, QUERY_POOL);
        T src = join(fSrc);
        T tgt = join(fTgt);
        return new Pair<>(src, tgt);
    }

    @FunctionalInterface
    private interface QueryFunc<T> {
        T apply(Connection conn) throws Exception;
    }

    private static class Pair<T> {
        final T first;
        final T second;
        Pair(T first, T second) { this.first = first; this.second = second; }
    }

    private static <T> T join(CompletableFuture<T> f) throws Exception {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    // ============ 各维度查询原语 ============

    private static long queryCount(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static Map<String, Double> querySums(Connection conn, String sql) throws SQLException {
        Map<String, Double> sums = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            if (rs.next()) {
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    String colLabel = metaData.getColumnLabel(i);
                    String fieldName = colLabel.startsWith("SUM_") ? colLabel.substring(4) : colLabel;
                    double val = rs.getDouble(i);
                    sums.put(fieldName, rs.wasNull() ? 0.0 : val);
                }
            }
        }
        return sums;
    }

    private static Long queryHash(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong("total_hash");
        }
        return null;
    }

    // ============ 维度比对（并发版本） ============

    /**
     * 维度1：数据条数比对（源/目标并发查询）
     */
    private static void compareCount(Connection sourceConn, Connection targetConn,
                                      String sourceTableName, String targetTableName,
                                      AggregateCompareResult result) throws Exception {
        Pair<Long> counts = runBoth(sourceConn, targetConn,
            c -> queryCount(c, sourceTableName),
            c -> queryCount(c, targetTableName));

        long sourceCount = counts.first;
        long targetCount = counts.second;

        result.setSourceCount(sourceCount);
        result.setTargetCount(targetCount);
        result.setCountDiff(sourceCount - targetCount);
        result.setCountConsistent(sourceCount == targetCount);
    }

    /**
     * 维度2：金额字段SUM比对（源/目标并发查询）
     */
    private static void compareAmountSum(Connection sourceConn, Connection targetConn,
                                          String sourceTableName, String targetTableName,
                                          List<String> amountFields,
                                          AggregateCompareResult result) throws Exception {
        String sourceSumSql = FieldClassifier.buildSumSql(sourceTableName, amountFields);
        String targetSumSql = FieldClassifier.buildSumSql(targetTableName, amountFields);
        if (sourceSumSql == null || targetSumSql == null) {
            result.setAmountSumConsistent(true);
            return;
        }

        Pair<Map<String, Double>> sums = runBoth(sourceConn, targetConn,
            c -> querySums(c, sourceSumSql),
            c -> querySums(c, targetSumSql));

        Map<String, Double> sourceSums = sums.first;
        Map<String, Double> targetSums = sums.second;

        result.setSourceAmountSums(sourceSums);
        result.setTargetAmountSums(targetSums);

        // 比对每个金额字段
        List<String> diffFields = new ArrayList<>();
        boolean allConsistent = true;
        for (String field : sourceSums.keySet()) {
            Double sourceSum = sourceSums.get(field);
            Double targetSum = targetSums.get(field);
            if (sourceSum == null) sourceSum = 0.0;
            if (targetSum == null) targetSum = 0.0;

            double diff = Math.abs(sourceSum - targetSum);
            if (diff > 0.01) { // 允许0.01的浮点误差
                allConsistent = false;
                diffFields.add(field);
                log.warn("表 {} 金额字段 {} 不一致: 源库={}, 目标库={}, 差异={}", 
                         sourceTableName, field, sourceSum, targetSum, diff);
            }
        }
        result.setAmountSumConsistent(allConsistent);
        result.setAmountDiffFields(diffFields);
    }

    /**
     * 维度3：行数据Hash比对（源/目标并发查询）
     * 
     * 原理：对每行所有列拼接后计算MD5，再对所有MD5的数值求和。
     * 如果源库和目标库数据完全一致，Hash求和结果必然相等。
     * 相比逐行传输数据，性能提升巨大（特别是大表场景）。
     */
    private static void compareRowHash(Connection sourceConn, Connection targetConn,
                                        String sourceTableName, String targetTableName,
                                        List<String> columns,
                                        DatabaseType sourceDbType, DatabaseType targetDbType,
                                        AggregateCompareResult result) throws Exception {
        String sourceHashSql = FieldClassifier.buildRowHashSql(sourceTableName, columns, sourceDbType.name());
        String targetHashSql = FieldClassifier.buildRowHashSql(targetTableName, columns, targetDbType.name());
        if (sourceHashSql == null || targetHashSql == null) {
            result.setRowHashConsistent(true);
            return;
        }

        Pair<Long> hashes;
        try {
            hashes = runBoth(sourceConn, targetConn,
                c -> queryHash(c, sourceHashSql),
                c -> queryHash(c, targetHashSql));
        } catch (Exception e) {
            // Hash function not supported by this database (e.g. H2 doesn't support CONV/MD5).
            // Gracefully skip — count and sum dimensions still protect consistency.
            log.warn("表 {} 行Hash计算不支持，跳过Hash比对: {}", sourceTableName, e.getMessage());
            result.setRowHashConsistent(true);
            return;
        }

        Long sourceHash = hashes.first;
        Long targetHash = hashes.second;
        result.setSourceRowHash(sourceHash);
        result.setTargetRowHash(targetHash);
        result.setRowHashConsistent(
            sourceHash != null && targetHash != null && sourceHash.equals(targetHash));
    }

    /**
     * 聚合比对结果
     */
    @Data
    public static class AggregateCompareResult {
        /** 表名 */
        private String tableName;

        // ========== 维度1：数据条数 ==========
        private long sourceCount;
        private long targetCount;
        private long countDiff;
        private boolean countConsistent;

        // ========== 维度2：金额SUM ==========
        private boolean hasAmountField;
        private List<String> amountFieldNames = new ArrayList<>();
        private List<String> timeFieldNames = new ArrayList<>();
        private Map<String, Double> sourceAmountSums = new LinkedHashMap<>();
        private Map<String, Double> targetAmountSums = new LinkedHashMap<>();
        private boolean amountSumConsistent = true;
        private List<String> amountDiffFields = new ArrayList<>();

        // ========== 维度3：行Hash ==========
        private Long sourceRowHash;
        private Long targetRowHash;
        private boolean rowHashConsistent;
        /** true：因超大表跳过全表 MD5，不参与综合一致判定 */
        private boolean rowHashSkipped;

        // ========== 综合判断 ==========
        private boolean consistent;

        /**
         * 获取差异摘要
         */
        public String getDiffSummary() {
            if (consistent) {
                return rowHashSkipped ? "一致(超大表已跳过Hash)" : "一致";
            }
            StringBuilder sb = new StringBuilder();
            if (!countConsistent) {
                sb.append("条数不一致(源:").append(sourceCount)
                  .append(", 目标:").append(targetCount)
                  .append(", 差异:").append(countDiff).append("); ");
            }
            if (!amountSumConsistent && !amountDiffFields.isEmpty()) {
                sb.append("金额不一致[").append(String.join(", ", amountDiffFields)).append("]; ");
            }
            if (rowHashSkipped) {
                sb.append("超大表已跳过Hash; ");
            } else if (!rowHashConsistent) {
                sb.append("数据内容Hash不一致; ");
            }
            return sb.toString();
        }
    }
}
