package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.compare.AggregateComparator;
import com.gcoll.dbcompare.compare.DataCompareExecutor;
import com.gcoll.dbcompare.compare.DatabaseCompareAdapter;
import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.dictionary.DatabaseRoutineReader;
import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.util.SchemaMappingHelper;
import com.gcoll.dbcompare.util.TableFilterHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 比对任务异步执行器（独立 Bean，避免 @Async 自调用失效）
 * 支持并行表比对、索引/函数/过程比对
 */
@Service
@Slf4j
public class CompareTaskRunner {

    private static final int PARALLEL_THREADS = 8; // 并行比对线程数
    private static final int SAVE_INTERVAL_SEC = 3; // 进度保存间隔（秒）

    @Autowired
    private CompareTaskRepository taskRepository;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private ReportService reportService;

    @Async("compareTaskExecutor")
    public void run(Long taskId) {
        CompareTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));

        task.setStatus("RUNNING");
        task.setStartTime(java.time.LocalDateTime.now());
        taskRepository.save(task);

        try {
            DataSourceConfig sourceConfig = dataSourceService.getDataSourceById(task.getSourceDataSourceId());
            DataSourceConfig targetConfig = dataSourceService.getDataSourceById(task.getTargetDataSourceId());

            DatabaseType sourceDbType = sourceConfig.getDbType();
            DatabaseType targetDbType = targetConfig.getDbType();

            // 优先使用任务中指定的 schema
            String sourceSchema;
            if (task.getSourceSchema() != null && task.getSourceSchema().isEmpty()) {
                sourceSchema = null;
            } else if (task.getSourceSchema() != null && !task.getSourceSchema().isEmpty()) {
                sourceSchema = task.getSourceSchema();
            } else {
                sourceSchema = sourceConfig.getSchema();
            }
            String targetSchema;
            if (task.getTargetSchema() != null && task.getTargetSchema().isEmpty()) {
                targetSchema = null;
            } else if (task.getTargetSchema() != null && !task.getTargetSchema().isEmpty()) {
                targetSchema = task.getTargetSchema();
            } else {
                targetSchema = targetConfig.getSchema();
            }
            boolean isMultiSource = sourceSchema == null || sourceSchema.isEmpty() || sourceSchema.contains(",");
            boolean isMultiTarget = targetSchema == null || targetSchema.isEmpty() || targetSchema.contains(",");

            // 任务级 Hikari 池：表间借还复用；成功/失败结束时 try-with-resources 关闭
            try (SyncJdbcPools pools = dataSourceService.createSyncPools(
                    sourceConfig, targetConfig, PARALLEL_THREADS, false)) {
                log.info("比对任务连接池已创建: {}", pools.describeStats());

                List<String> tables;
                Map<String, String> tgtTableCatalogMap;
                Map<String, String> schemaMap;
                try (Connection metaSourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                     Connection metaTargetConn = pools.borrowTarget(targetSchema, isMultiTarget, false)) {
                    tables = TableFilterHelper.resolveTables(
                            metaSourceConn, sourceSchema, sourceDbType, task);
                    task.setTotalTables(tables.size());
                    taskRepository.save(task);
                    tgtTableCatalogMap = isMultiTarget
                            ? SchemaMappingHelper.buildTargetTableCatalogMap(metaTargetConn, targetSchema, targetDbType)
                            : Collections.emptyMap();
                    schemaMap = SchemaMappingHelper.parseSchemaMapping(task.getSchemaMapping());
                }

                log.info("开始比对任务: taskId={}, 模式={}, 总表数={}, 并行度={}",
                        taskId, task.getCompareMode(), tables.size(), PARALLEL_THREADS);

                Map<String, Object> compareResult = new ConcurrentHashMap<>();
                Map<String, Object> globalResult = new ConcurrentHashMap<>();

                if (!tables.isEmpty()) {
                    ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
                    List<Future<?>> futures = new ArrayList<>();
                    AtomicInteger processed = new AtomicInteger(0);
                    AtomicInteger lastSaveTime = new AtomicInteger((int) (System.currentTimeMillis() / 1000));

                    for (String tableName : tables) {
                        SchemaMappingHelper.CatalogPair catalogs = SchemaMappingHelper.resolveCatalogs(
                                tableName, sourceSchema, targetSchema,
                                isMultiSource, isMultiTarget, schemaMap, tgtTableCatalogMap);
                        final String fSrcCat = catalogs.srcCat;
                        final String fTgtCat = catalogs.tgtCat;
                        final String fPureTableName = catalogs.pureTableName;
                        futures.add(executor.submit(() -> {
                            // 表开始即刷新进度，避免单表长时间聚合时 UI 一直停在「初始化 / 0%」
                            task.setCurrentTable(tableName + " · 比对中");
                            if (task.getProgress() == null || task.getProgress() < 5) {
                                task.setProgress(5);
                            }
                            synchronized (taskRepository) {
                                taskRepository.save(task);
                            }

                            Connection sourceConn = null;
                            Connection targetConn = null;
                            try {
                                sourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                                targetConn = pools.borrowTarget(targetSchema, isMultiTarget, false);
                                Map<String, Object> tableResult = compareTable(
                                        sourceConn, targetConn, tableName, fPureTableName, fSrcCat, fTgtCat,
                                        sourceDbType, targetDbType, task);
                                compareResult.put(tableName, tableResult);
                            } catch (Exception e) {
                                log.error("比对表 {} 失败: {}", tableName, e.getMessage());
                                Map<String, Object> errResult = new LinkedHashMap<>();
                                errResult.put("error", e.getMessage());
                                errResult.put("consistent", false);
                                compareResult.put(tableName, errResult);
                            } finally {
                                SyncJdbcPools.release(sourceConn);
                                SyncJdbcPools.release(targetConn);
                            }

                            int done = processed.incrementAndGet();
                            task.setCurrentTable(tableName);
                            task.setProcessedTables(done);
                            task.setProgress(done * 100 / Math.max(tables.size(), 1));

                            int nowSec = (int) (System.currentTimeMillis() / 1000);
                            if (nowSec - lastSaveTime.get() > SAVE_INTERVAL_SEC || done == tables.size()) {
                                synchronized (taskRepository) {
                                    taskRepository.save(task);
                                }
                                lastSaveTime.set(nowSec);
                            }

                            log.info("已完成表比对: {}/{} - {} ", done, tables.size(), tableName);
                        }));
                    }

                    for (Future<?> f : futures) {
                        try {
                            f.get(30, TimeUnit.MINUTES);
                        } catch (TimeoutException e) {
                            log.error("表比对超时", e);
                        }
                    }
                    executor.shutdown();
                    try {
                        executor.awaitTermination(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (Boolean.TRUE.equals(task.getCompareFunction())) {
                    try (Connection funcSourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                         Connection funcTargetConn = pools.borrowTarget(targetSchema, isMultiTarget, false)) {
                        log.info("开始函数比对...");
                        List<DatabaseRoutineReader.RoutineMeta> sourceFuncs =
                                DatabaseRoutineReader.readFunctions(funcSourceConn, sourceSchema, sourceDbType);
                        List<DatabaseRoutineReader.RoutineMeta> targetFuncs =
                                DatabaseRoutineReader.readFunctions(funcTargetConn, targetSchema, targetDbType);
                        DatabaseRoutineReader.RoutineCompareResult funcResult =
                                DatabaseRoutineReader.compareRoutines(sourceFuncs, targetFuncs, "FUNCTION");
                        globalResult.put("__functions__", routineResultToMap(funcResult));
                        log.info("函数比对完成: 源库={}, 目标库={}, 一致={}",
                                funcResult.getSourceCount(), funcResult.getTargetCount(), funcResult.isConsistent());
                    } catch (Exception e) {
                        log.error("函数比对失败", e);
                        Map<String, Object> errMap = new LinkedHashMap<>();
                        errMap.put("error", e.getMessage());
                        errMap.put("consistent", false);
                        globalResult.put("__functions__", errMap);
                    }
                }

                if (Boolean.TRUE.equals(task.getCompareProcedure())) {
                    try (Connection procSourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                         Connection procTargetConn = pools.borrowTarget(targetSchema, isMultiTarget, false)) {
                        log.info("开始存储过程比对...");
                        List<DatabaseRoutineReader.RoutineMeta> sourceProcs =
                                DatabaseRoutineReader.readProcedures(procSourceConn, sourceSchema, sourceDbType);
                        List<DatabaseRoutineReader.RoutineMeta> targetProcs =
                                DatabaseRoutineReader.readProcedures(procTargetConn, targetSchema, targetDbType);
                        DatabaseRoutineReader.RoutineCompareResult procResult =
                                DatabaseRoutineReader.compareRoutines(sourceProcs, targetProcs, "PROCEDURE");
                        globalResult.put("__procedures__", routineResultToMap(procResult));
                        log.info("存储过程比对完成: 源库={}, 目标库={}, 一致={}",
                                procResult.getSourceCount(), procResult.getTargetCount(), procResult.isConsistent());
                    } catch (Exception e) {
                        log.error("存储过程比对失败", e);
                        Map<String, Object> errMap = new LinkedHashMap<>();
                        errMap.put("error", e.getMessage());
                        errMap.put("consistent", false);
                        globalResult.put("__procedures__", errMap);
                    }
                }

                compareResult.putAll(globalResult);

                String reportPath = reportService.generateReport(task, compareResult);
                task.setReportPath(reportPath);
                task.setStatus("SUCCESS");
                task.setProgress(100);
                task.setEndTime(java.time.LocalDateTime.now());
                taskRepository.save(task);
                log.info("比对任务完成: taskId={}, 报告={}, 池={}", taskId, reportPath, pools.describeStats());
            }

        } catch (Exception e) {
            log.error("比对任务失败: taskId=" + taskId, e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setEndTime(java.time.LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    private Map<String, Object> compareTable(
            Connection sourceConn, Connection targetConn,
            String tableName, String pureTableName, String sourceCatalog, String targetCatalog,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            CompareTask task) {

        Map<String, Object> result = new LinkedHashMap<>();
        boolean allConsistent = true;
        String mode = task.getCompareMode() != null ? task.getCompareMode().toUpperCase() : "FULL";
        result.put("compareMode", mode);

        try {
            // 表结构比对
            if (Boolean.TRUE.equals(task.getCompareSchema())) {
                boolean schemaConsistent = DatabaseCompareAdapter.compareTableSchema(
                    sourceConn, targetConn, pureTableName, sourceDbType, targetDbType,
                    sourceCatalog, targetCatalog);
                result.put("schema", schemaConsistent ? "一致" : "不一致");
                if (!schemaConsistent) {
                    allConsistent = false;
                }
            }

            // 索引比对
            if (Boolean.TRUE.equals(task.getCompareIndex())) {
                try {
                    Map<String, Object> indexResult = DatabaseCompareAdapter.compareIndexes(
                        sourceConn, targetConn, pureTableName, sourceDbType, targetDbType,
                        sourceCatalog, targetCatalog);
                    result.put("indexes", indexResult);
                    if (Boolean.FALSE.equals(indexResult.get("consistent"))) {
                        allConsistent = false;
                    }
                } catch (Exception e) {
                    log.warn("表 {} 索引比对失败: {}", tableName, e.getMessage());
                    Map<String, Object> idxErrMap = new LinkedHashMap<>();
                    idxErrMap.put("error", e.getMessage());
                    idxErrMap.put("consistent", false);
                    result.put("indexes", idxErrMap);
                    allConsistent = false;
                }
            }

            // 读取表元数据（多库模式下传入 catalog）
            DatabaseMetadataReader.TableMetadata tableMeta;
            if (sourceCatalog != null) {
                tableMeta = DatabaseMetadataReader.readTableMetadata(sourceConn, pureTableName, sourceDbType, sourceCatalog);
            } else {
                tableMeta = DatabaseMetadataReader.readTableMetadata(sourceConn, pureTableName, sourceDbType);
            }

            // 构建完全限定的 SQL 表名
            String srcQualified = buildQualifiedName(sourceCatalog, pureTableName, sourceDbType);
            String tgtQualified = buildQualifiedName(targetCatalog, pureTableName, targetDbType);

            // 数据量&数据内容比对
            if (Boolean.TRUE.equals(task.getCompareCount()) || Boolean.TRUE.equals(task.getCompareData())) {
                switch (mode) {
                    case "SAMPLE":
                        applySampleCompare(sourceConn, targetConn, srcQualified, tgtQualified, tableMeta,
                            sourceDbType, targetDbType, task, result);
                        break;
                    case "INCREMENTAL":
                        applyIncrementalCompare(sourceConn, targetConn, srcQualified, tgtQualified, tableMeta,
                            sourceDbType, targetDbType, task, result);
                        break;
                    default:
                        applyFullCompare(sourceConn, targetConn, srcQualified, tgtQualified, tableMeta,
                            sourceDbType, targetDbType, task, result);
                        break;
                }
                allConsistent = isResultConsistent(result, allConsistent);
            }

            result.put("consistent", allConsistent);

        } catch (Exception e) {
            log.error("比对表 {} 失败", tableName, e);
            result.put("error", e.getMessage());
            result.put("consistent", false);
        }

        return result;
    }

    /**
     * 构建完全限定的 SQL 表名
     * 单库模式：bareTableName
     * 多库/全库模式：`catalog`.`tableName`
     */
    private String buildQualifiedName(String catalog, String pureTableName, DatabaseType dbType) {
        if (catalog == null || catalog.isEmpty()) {
            return pureTableName;
        }
        // MySQL/GoldenDB 使用反引号限定
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            return "`" + catalog + "`.`" + pureTableName + "`";
        }
        // PostgreSQL/Oracle 使用 schema.table
        if (dbType == DatabaseType.POSTGRESQL || dbType == DatabaseType.ORACLE) {
            return catalog + "." + pureTableName;
        }
        // SQL Server 使用 catalog.schema.table (这里 catalog 作为 schema)
        if (dbType == DatabaseType.SQLSERVER) {
            return catalog + ".dbo." + pureTableName;
        }
        return catalog + "." + pureTableName;
    }

    private void applyFullCompare(
            Connection sourceConn, Connection targetConn,
            String srcQualified, String tgtQualified, DatabaseMetadataReader.TableMetadata tableMeta,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            CompareTask task, Map<String, Object> result) throws Exception {

        AggregateComparator.AggregateCompareResult aggResult = AggregateComparator.compare(
            sourceConn, targetConn, srcQualified, tgtQualified, tableMeta.getColumns(),
            sourceDbType, targetDbType);

        putAggregateResult(result, aggResult);

        boolean allConsistent = aggResult.isConsistent();
        boolean hugeTable = AggregateComparator.shouldSkipRowHash(
                aggResult.getSourceCount(), aggResult.getTargetCount());
        if (!allConsistent && Boolean.TRUE.equals(task.getCompareData())
                && isDetailCompareEnabled(task) && !hugeTable) {
            try {
                result.put("detail", performDetailCompare(
                    sourceConn, targetConn, srcQualified, tgtQualified, sourceDbType, targetDbType, task));
            } catch (Exception e) {
                log.warn("表 {} 详细比对失败: {}", srcQualified, e.getMessage());
            }
        } else if (hugeTable && !allConsistent && Boolean.TRUE.equals(task.getCompareData())) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("type", "跳过");
            skipped.put("reason", "超大表（≥" + AggregateComparator.SKIP_ROW_HASH_ROW_THRESHOLD
                    + " 行）已跳过逐行详情比对，请以条数/金额为准或抽样比对");
            result.put("detail", skipped);
        }
    }

    private void applySampleCompare(
            Connection sourceConn, Connection targetConn,
            String srcQualified, String tgtQualified, DatabaseMetadataReader.TableMetadata tableMeta,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            CompareTask task, Map<String, Object> result) throws Exception {

        double sampleRate = task.getSampleRate() != null ? task.getSampleRate() : 0.1;
        Map<String, Object> sampleResult = DataCompareExecutor.compareSample(
            sourceConn, targetConn, srcQualified, tgtQualified, tableMeta, sourceDbType, targetDbType, sampleRate);
        result.putAll(sampleResult);

        if (Boolean.TRUE.equals(task.getCompareCount())) {
            long sourceCount = CountFromResult.getSourceCount(sampleResult);
            long targetCount = CountFromResult.getTargetCount(sampleResult);
            if (sourceCount >= 0 && targetCount >= 0 && sourceCount != targetCount) {
                Map<String, Object> countInfo = new LinkedHashMap<>();
                countInfo.put("sourceCount", sourceCount);
                countInfo.put("targetCount", targetCount);
                countInfo.put("diff", sourceCount - targetCount);
                countInfo.put("consistent", false);
                result.put("count", countInfo);
            }
        }
    }

    private void applyIncrementalCompare(
            Connection sourceConn, Connection targetConn,
            String srcQualified, String tgtQualified, DatabaseMetadataReader.TableMetadata tableMeta,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            CompareTask task, Map<String, Object> result) throws Exception {

        Map<String, Object> incResult = DataCompareExecutor.compareIncremental(
            sourceConn, targetConn, srcQualified, tgtQualified, tableMeta, sourceDbType, targetDbType);
        result.putAll(incResult);
    }

    private void putAggregateResult(Map<String, Object> result, AggregateComparator.AggregateCompareResult aggResult) {
        Map<String, Object> countInfo = new LinkedHashMap<>();
        countInfo.put("sourceCount", aggResult.getSourceCount());
        countInfo.put("targetCount", aggResult.getTargetCount());
        countInfo.put("diff", aggResult.getCountDiff());
        countInfo.put("consistent", aggResult.isCountConsistent());
        result.put("count", countInfo);

        if (aggResult.isHasAmountField()) {
            Map<String, Object> amountInfo = new LinkedHashMap<>();
            amountInfo.put("amountFields", aggResult.getAmountFieldNames());
            amountInfo.put("sourceSums", aggResult.getSourceAmountSums());
            amountInfo.put("targetSums", aggResult.getTargetAmountSums());
            amountInfo.put("consistent", aggResult.isAmountSumConsistent());
            amountInfo.put("diffFields", aggResult.getAmountDiffFields());
            result.put("amount", amountInfo);
        } else {
            result.put("amount", "无金额字段");
        }

        Map<String, Object> hashInfo = new LinkedHashMap<>();
        hashInfo.put("sourceHash", aggResult.getSourceRowHash());
        hashInfo.put("targetHash", aggResult.getTargetRowHash());
        hashInfo.put("skipped", aggResult.isRowHashSkipped());
        if (aggResult.isRowHashSkipped()) {
            hashInfo.put("consistent", true);
            hashInfo.put("message", "超大表已跳过全表 MD5 Hash（≥"
                    + AggregateComparator.SKIP_ROW_HASH_ROW_THRESHOLD + " 行）");
        } else {
            hashInfo.put("consistent", aggResult.isRowHashConsistent());
        }
        result.put("rowHash", hashInfo);
    }

    private boolean isResultConsistent(Map<String, Object> result, boolean schemaOk) {
        if (!schemaOk) {
            return false;
        }
        // 检查索引比对
        Object idxObj = result.get("indexes");
        if (idxObj instanceof Map && Boolean.FALSE.equals(((Map<?,?>) idxObj).get("consistent"))) {
            return false;
        }
        Boolean consistent = (Boolean) result.get("consistent");
        if (consistent != null) {
            return consistent;
        }
        return isSectionConsistent(result.get("count"))
            && isSectionConsistent(result.get("amount"))
            && isSectionConsistent(result.get("rowHash"))
            && isSectionConsistent(result.get("sample"))
            && isSectionConsistent(result.get("incremental"));
    }

    @SuppressWarnings("unchecked")
    private boolean isSectionConsistent(Object section) {
        if (section instanceof Map) {
            Boolean c = (Boolean) ((Map<String, Object>) section).get("consistent");
            return c == null || c;
        }
        return true;
    }

    private boolean isDetailCompareEnabled(CompareTask task) {
        return task.getBatchSize() != null && task.getBatchSize() <= 5000;
    }

    private Map<String, Object> performDetailCompare(
            Connection sourceConn, Connection targetConn,
            String tableName, String targetTableName,
            DatabaseType sourceDbType, DatabaseType targetDbType,
            CompareTask task) throws Exception {

        Map<String, Object> detail = new LinkedHashMap<>();
        List<String> primaryKeys = DatabaseCompareAdapter.getPrimaryKeys(sourceConn, tableName, sourceDbType);

        if (!primaryKeys.isEmpty()) {
            int maxDiffRows = 100; // 最多收集100条差异行详情
            Map<String, Object> detailResult = DatabaseCompareAdapter.compareTableDataWithDetails(
                sourceConn, targetConn, tableName, targetTableName, primaryKeys,
                sourceDbType, targetDbType,
                task.getBatchSize() != null ? task.getBatchSize() : 1000,
                maxDiffRows);
            detail.put("type", "逐行比对");
            detail.putAll(detailResult);
        } else {
            detail.put("type", "跳过");
            detail.put("reason", "无主键，无法逐行比对");
        }
        return detail;
    }

    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("关闭连接失败", e);
            }
        }
    }

    /** 从抽样/增量结果中提取条数 */
    private static class CountFromResult {
        @SuppressWarnings("unchecked")
        static long getSourceCount(Map<String, Object> result) {
            Object count = result.get("count");
            if (count instanceof Map) {
                Object v = ((Map<String, Object>) count).get("sourceCount");
                return v instanceof Number ? ((Number) v).longValue() : -1;
            }
            return -1;
        }

        @SuppressWarnings("unchecked")
        static long getTargetCount(Map<String, Object> result) {
            Object count = result.get("count");
            if (count instanceof Map) {
                Object v = ((Map<String, Object>) count).get("targetCount");
                return v instanceof Number ? ((Number) v).longValue() : -1;
            }
            return -1;
        }
    }

    /** 将 RoutineCompareResult POJO 转为 Map（供 ReportService 使用） */
    private Map<String, Object> routineResultToMap(DatabaseRoutineReader.RoutineCompareResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("routineType", result.getRoutineType());
        map.put("sourceCount", result.getSourceCount());
        map.put("targetCount", result.getTargetCount());
        map.put("commonCount", result.getCommonCount());
        map.put("onlyInSource", result.getOnlyInSource());
        map.put("onlyInTarget", result.getOnlyInTarget());
        map.put("consistent", result.isConsistent());
        return map;
    }
}
