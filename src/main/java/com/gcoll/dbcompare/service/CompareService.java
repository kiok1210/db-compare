package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.util.SchemaMappingHelper;
import com.gcoll.dbcompare.util.TaskNameHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库比对服务（任务调度入口）
 */
@Service
@Slf4j
public class CompareService {

    private static final int DEFAULT_ROW_DIFF_LIMIT = 100;
    private static final int MAX_ROW_DIFF_LIMIT = 500;
    private static final int ROW_DIFF_BATCH = 1000;

    @Autowired
    private CompareTaskRepository taskRepository;

    @Autowired
    private CompareTaskRunner compareTaskRunner;

    @Autowired
    private ReportService reportService;

    @Autowired
    private DataSourceService dataSourceService;

    /**
     * 提交比对任务（异步执行）
     */
    public Long executeCompare(CompareTask task) {
        task.setStatus("PENDING");
        task.setProgress(0);
        CompareTask savedTask = taskRepository.save(task);
        compareTaskRunner.run(savedTask.getId());
        return savedTask.getId();
    }

    public CompareTask getTaskById(Long taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
    }

    public Map<String, Object> getProgress(Long taskId) {
        CompareTask task = getTaskById(taskId);
        Map<String, Object> progress = new HashMap<>();
        progress.put("status", task.getStatus());
        progress.put("progress", task.getProgress());
        progress.put("currentTable", task.getCurrentTable());
        progress.put("processedTables", task.getProcessedTables());
        progress.put("totalTables", task.getTotalTables());
        progress.put("errorMessage", task.getErrorMessage());
        progress.put("reportPath", task.getReportPath());
        progress.put("taskName", task.getTaskName());
        progress.put("startTime", task.getStartTime() != null ? task.getStartTime().toString() : null);
        progress.put("endTime", task.getEndTime() != null ? task.getEndTime().toString() : null);
        return progress;
    }

    public Map<String, Object> getReport(Long taskId) {
        CompareTask task = getTaskById(taskId);
        return reportService.loadReport(task);
    }

    /**
     * 按主键归并查询单表详细差异（主键/索引/状态/业务日期/金额关注列 + 定位 SQL）。
     *
     * @param tableKey 报告中的表名，建议 schema::table
     * @param limit    每侧最多返回条数，默认 100，上限 500
     */
    public Map<String, Object> findRowDiffDetails(Long taskId, String tableKey, Integer limit) {
        if (tableKey == null || tableKey.trim().isEmpty()) {
            throw new IllegalArgumentException("请指定表名");
        }
        String table = tableKey.trim();
        int maxDiffRows = limit == null ? DEFAULT_ROW_DIFF_LIMIT
                : Math.min(Math.max(limit, 1), MAX_ROW_DIFF_LIMIT);

        CompareTask task = getTaskById(taskId);
        if (task.getSourceDataSourceId() == null || task.getTargetDataSourceId() == null) {
            throw new RuntimeException("比对任务缺少源/目标数据源");
        }

        DataSourceConfig sourceConfig = dataSourceService.getDataSourceById(task.getSourceDataSourceId());
        DataSourceConfig targetConfig = dataSourceService.getDataSourceById(task.getTargetDataSourceId());
        DatabaseType sourceDbType = sourceConfig.getDbType();
        DatabaseType targetDbType = targetConfig.getDbType();

        String sourceSchema = resolveSchema(task.getSourceSchema(), sourceConfig.getSchema());
        String targetSchema = resolveSchema(task.getTargetSchema(), targetConfig.getSchema());
        boolean isMultiSource = sourceSchema == null || sourceSchema.isEmpty() || sourceSchema.contains(",");
        boolean isMultiTarget = targetSchema == null || targetSchema.isEmpty() || targetSchema.contains(",");

        Map<String, String> schemaMap = SchemaMappingHelper.parseSchemaMapping(task.getSchemaMapping());

        try (SyncJdbcPools pools = dataSourceService.createSyncPools(sourceConfig, targetConfig, 2, false)) {
            Map<String, String> tgtTableCatalogMap = Collections.emptyMap();
            if (isMultiTarget) {
                try (Connection metaTargetConn = pools.borrowTarget(targetSchema, true, false)) {
                    tgtTableCatalogMap = SchemaMappingHelper.buildTargetTableCatalogMap(
                            metaTargetConn, targetSchema, targetDbType);
                }
            }

            SchemaMappingHelper.CatalogPair catalogs = SchemaMappingHelper.resolveCatalogs(
                    table, sourceSchema, targetSchema,
                    isMultiSource, isMultiTarget, schemaMap, tgtTableCatalogMap);
            String pureTable = catalogs.pureTableName;
            String srcQualified = buildQualifiedName(catalogs.srcCat, pureTable, sourceDbType);
            String tgtQualified = buildQualifiedName(catalogs.tgtCat, pureTable, targetDbType);

            try (Connection sourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                 Connection targetConn = pools.borrowTarget(targetSchema, isMultiTarget, false)) {
                Map<String, Object> detail = com.gcoll.dbcompare.compare.DetailedTableComparator.compareDetailed(
                        sourceConn, targetConn,
                        pureTable, catalogs.srcCat, catalogs.tgtCat,
                        srcQualified, tgtQualified,
                        sourceDbType, targetDbType,
                        ROW_DIFF_BATCH, maxDiffRows);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("table", table);
                out.putAll(detail);
                return out;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询表差异数据失败: taskId={}, table={}", taskId, table, e);
            throw new RuntimeException("查询差异数据失败: " + e.getMessage(), e);
        }
    }

    private static String resolveSchema(String taskSchema, String configSchema) {
        if (taskSchema != null && taskSchema.isEmpty()) {
            return null;
        }
        if (taskSchema != null && !taskSchema.isEmpty()) {
            return taskSchema;
        }
        return configSchema;
    }

    private static String buildQualifiedName(String catalog, String pureTableName, DatabaseType dbType) {
        if (catalog == null || catalog.isEmpty()) {
            return pureTableName;
        }
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            return "`" + catalog + "`.`" + pureTableName + "`";
        }
        if (dbType == DatabaseType.POSTGRESQL || dbType == DatabaseType.ORACLE) {
            return catalog + "." + pureTableName;
        }
        if (dbType == DatabaseType.SQLSERVER) {
            return catalog + ".dbo." + pureTableName;
        }
        return catalog + "." + pureTableName;
    }

    /**
     * 取消比对任务
     */
    public void cancelTask(Long taskId) {
        CompareTask task = getTaskById(taskId);
        if ("RUNNING".equals(task.getStatus()) || "PENDING".equals(task.getStatus())) {
            task.setStatus("CANCELLED");
            task.setEndTime(java.time.LocalDateTime.now());
            taskRepository.save(task);
            log.info("任务已取消: {}", taskId);
        }
    }

    /**
     * 基于历史任务配置重新执行比对
     */
    public Long reExecute(Long taskId) {
        CompareTask original = getTaskById(taskId);

        // 创建新任务，复制原始配置
        CompareTask newTask = new CompareTask();
        newTask.setTaskName(TaskNameHelper.buildReExecuteName(original.getTaskName()));
        newTask.setSourceDataSourceId(original.getSourceDataSourceId());
        newTask.setTargetDataSourceId(original.getTargetDataSourceId());
        newTask.setCompareMode(original.getCompareMode());
        newTask.setSampleRate(original.getSampleRate());
        newTask.setCompareSchema(original.getCompareSchema());
        newTask.setCompareCount(original.getCompareCount());
        newTask.setCompareData(original.getCompareData());
        newTask.setCompareIndex(original.getCompareIndex());
        newTask.setCompareFunction(original.getCompareFunction());
        newTask.setCompareProcedure(original.getCompareProcedure());
        newTask.setBatchSize(original.getBatchSize());
        newTask.setIncludeTables(original.getIncludeTables());
        newTask.setExcludeTables(original.getExcludeTables());
        newTask.setSourceSchema(original.getSourceSchema());
        newTask.setTargetSchema(original.getTargetSchema());
        newTask.setSchemaMapping(original.getSchemaMapping());

        // 提交执行
        newTask.setStatus("PENDING");
        newTask.setProgress(0);
        CompareTask savedTask = taskRepository.save(newTask);
        compareTaskRunner.run(savedTask.getId());
        log.info("重新执行任务: 原始ID={}, 新任务ID={}", taskId, savedTask.getId());
        return savedTask.getId();
    }

    /**
     * 删除比对任务
     */
    public void deleteTask(Long taskId) {
        taskRepository.deleteById(taskId);
        log.info("任务已删除: {}", taskId);
    }
}
