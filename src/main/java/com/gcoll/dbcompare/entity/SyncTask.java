package com.gcoll.dbcompare.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "dbcompare_sync_task")
public class SyncTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 部分表成功、部分失败 */
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** @deprecated 兼容旧值，等同 FULL_DML */
    public static final String MODE_FULL = "FULL";
    /** 全量（仅 DML）：分批 DELETE + INSERT，默认 */
    public static final String MODE_FULL_DML = "FULL_DML";
    /** 全量（DDL）：TRUNCATE 或 CREATE LIKE + RENAME，需目标库 DDL 权限 */
    public static final String MODE_FULL_DDL = "FULL_DDL";
    /** 增量：不清空，仅插入（MySQL/GoldenDB 使用 INSERT IGNORE 跳过主键冲突） */
    public static final String MODE_INCREMENTAL = "INCREMENTAL";
    /**
     * 增量合并：按主键 UPDATE 已有行 + INSERT 新行，不清空表。
     * 适合差异不大的场景；MySQL/GoldenDB 使用 INSERT … ON DUPLICATE KEY UPDATE。
     */
    public static final String MODE_PK_UPSERT = "PK_UPSERT";
    /**
     * 数据传输（类 Navicat Data Transfer）：清空目标表后全量写入源数据（等同 DML 全量策略）。
     */
    public static final String MODE_DATA_TRANSFER = "DATA_TRANSFER";
    /**
     * 数据同步（类 Navicat Data Synchronization）：按主键比对后 INSERT 新行、UPDATE 差异行、DELETE 目标多余行。
     */
    public static final String MODE_DATA_SYNC = "DATA_SYNC";
    /**
     * 调用本机官方 DataX（mysqlreader→mysqlwriter）做全量同步；需在参数设置中配置 DataX 安装目录。
     */
    public static final String MODE_DATAX = "DATAX";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String taskName;

    @Column(nullable = false)
    private Long sourceDataSourceId;

    @Column(nullable = false)
    private Long targetDataSourceId;

    @Column(length = 500)
    private String sourceSchema;

    @Column(length = 500)
    private String targetSchema;

    @Column(columnDefinition = "TEXT")
    private String schemaMapping;

    @Column(columnDefinition = "TEXT")
    private String includeTables;

    @Column(columnDefinition = "TEXT")
    private String excludeTables;

    /** 同步模式：FULL_DML / FULL_DDL / INCREMENTAL / PK_UPSERT / DATA_TRANSFER / DATA_SYNC / DATAX */
    @Column(length = 32)
    private String syncMode = MODE_FULL_DML;

    @Column
    private Integer batchSize = 20000;

    /** 表级并行 / 本次任务连接数上限（与连接池 max 对齐） */
    public static final int MAX_PARALLEL_THREADS = 20;

    /** 表级并行同步线程数（范围 1–20；未指定时新建任务默认=同步表数且≤20） */
    @Column
    private Integer parallelThreads;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    @Column
    private Integer progress = 0;

    /**
     * 当前进度文案。写入前由 {@link com.gcoll.dbcompare.util.ProgressTextHelper} 摘要并截断，
     * 禁止再靠加宽列解决超长问题。
     */
    @Column(length = 500)
    private String currentTable;

    @Column
    private Integer processedTables = 0;

    @Column
    private Integer totalTables = 0;

    @Column
    private Integer successTables = 0;

    @Column
    private Integer failedTablesCount = 0;

    /**
     * 失败表明细 JSON：[{table,error,phase},...]
     * table 为源侧表名（多库时 schema::table），供失败重同步使用
     */
    @Column(columnDefinition = "TEXT")
    private String failedTables;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** 同步报告 HTML 相对路径，如 reports/sync/{id}/report.html */
    @Column(length = 500)
    private String reportPath;

    @Column
    private LocalDateTime createTime;

    @Column
    private LocalDateTime startTime;

    @Column
    private LocalDateTime endTime;

    @PrePersist
    protected void onCreate() {
        if (this.createTime == null) {
            this.createTime = LocalDateTime.now();
        }
        if (this.batchSize == null) {
            this.batchSize = 20000;
        }
        this.parallelThreads = clampParallelThreads(this.parallelThreads);
        this.syncMode = normalizeMode(this.syncMode);
    }

    public static int clampParallelThreads(Integer n) {
        if (n == null || n < 1) {
            return 8;
        }
        return Math.min(MAX_PARALLEL_THREADS, n);
    }

    /**
     * 按同步表数量给出本次任务默认连接数/并行度：与表数一致，大于 {@link #MAX_PARALLEL_THREADS} 则封顶。
     */
    public static int defaultParallelForTableCount(int tableCount) {
        if (tableCount < 1) {
            return 1;
        }
        return Math.min(MAX_PARALLEL_THREADS, tableCount);
    }

    /** 规范化同步模式；空值/未知值默认 FULL_DML */
    public static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return MODE_FULL_DML;
        }
        String m = mode.trim().toUpperCase();
        if (MODE_FULL.equals(m) || MODE_FULL_DML.equals(m)) {
            return MODE_FULL_DML;
        }
        if (MODE_FULL_DDL.equals(m) || MODE_INCREMENTAL.equals(m) || MODE_PK_UPSERT.equals(m)
                || MODE_DATA_TRANSFER.equals(m) || MODE_DATA_SYNC.equals(m) || MODE_DATAX.equals(m)) {
            return m;
        }
        return MODE_FULL_DML;
    }

    public static String modeDisplayName(String mode) {
        String m = normalizeMode(mode);
        if (MODE_FULL_DDL.equals(m)) {
            return "全量（DDL）";
        }
        if (MODE_INCREMENTAL.equals(m)) {
            return "仅插入";
        }
        if (MODE_PK_UPSERT.equals(m)) {
            return "增量同步";
        }
        if (MODE_DATA_TRANSFER.equals(m)) {
            return "数据传输";
        }
        if (MODE_DATA_SYNC.equals(m)) {
            return "差异同步";
        }
        if (MODE_DATAX.equals(m)) {
            return "DataX 工具同步";
        }
        return "全量（DML）";
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public Long getSourceDataSourceId() { return sourceDataSourceId; }
    public void setSourceDataSourceId(Long sourceDataSourceId) { this.sourceDataSourceId = sourceDataSourceId; }

    public Long getTargetDataSourceId() { return targetDataSourceId; }
    public void setTargetDataSourceId(Long targetDataSourceId) { this.targetDataSourceId = targetDataSourceId; }

    public String getSourceSchema() { return sourceSchema; }
    public void setSourceSchema(String sourceSchema) { this.sourceSchema = sourceSchema; }

    public String getTargetSchema() { return targetSchema; }
    public void setTargetSchema(String targetSchema) { this.targetSchema = targetSchema; }

    public String getSchemaMapping() { return schemaMapping; }
    public void setSchemaMapping(String schemaMapping) { this.schemaMapping = schemaMapping; }

    public String getIncludeTables() { return includeTables; }
    public void setIncludeTables(String includeTables) { this.includeTables = includeTables; }

    public String getExcludeTables() { return excludeTables; }
    public void setExcludeTables(String excludeTables) { this.excludeTables = excludeTables; }

    public String getSyncMode() { return syncMode; }
    public void setSyncMode(String syncMode) { this.syncMode = normalizeMode(syncMode); }

    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

    public Integer getParallelThreads() { return parallelThreads; }
    public void setParallelThreads(Integer parallelThreads) {
        // 允许 null 表示「未指定」，由 createDraft 按表数填充；入库前 onCreate 再 clamp
        this.parallelThreads = parallelThreads == null ? null : clampParallelThreads(parallelThreads);
    }

    public boolean isIncrementalMode() {
        return MODE_INCREMENTAL.equals(normalizeMode(syncMode));
    }

    public boolean isPkUpsertMode() {
        return MODE_PK_UPSERT.equals(normalizeMode(syncMode));
    }

    public boolean isDataTransferMode() {
        return MODE_DATA_TRANSFER.equals(normalizeMode(syncMode));
    }

    public boolean isDataSyncMode() {
        return MODE_DATA_SYNC.equals(normalizeMode(syncMode));
    }

    public boolean isDataxMode() {
        return MODE_DATAX.equals(normalizeMode(syncMode));
    }

    /** 不清空目标表的模式（增量插入 / 主键合并 / 数据同步） */
    public boolean isNoClearMode() {
        String m = normalizeMode(syncMode);
        return MODE_INCREMENTAL.equals(m) || MODE_PK_UPSERT.equals(m) || MODE_DATA_SYNC.equals(m);
    }

    public boolean isFullDdlMode() {
        return MODE_FULL_DDL.equals(normalizeMode(syncMode));
    }

    public boolean isFullDmlMode() {
        String m = normalizeMode(syncMode);
        return MODE_FULL_DML.equals(m) || MODE_FULL.equals(m) || MODE_DATA_TRANSFER.equals(m);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public String getCurrentTable() { return currentTable; }
    public void setCurrentTable(String currentTable) {
        this.currentTable = com.gcoll.dbcompare.util.ProgressTextHelper.abbreviate(
                currentTable, com.gcoll.dbcompare.util.ProgressTextHelper.MAX_LEN);
    }

    public Integer getProcessedTables() { return processedTables; }
    public void setProcessedTables(Integer processedTables) { this.processedTables = processedTables; }

    public Integer getTotalTables() { return totalTables; }
    public void setTotalTables(Integer totalTables) { this.totalTables = totalTables; }

    public Integer getSuccessTables() { return successTables; }
    public void setSuccessTables(Integer successTables) { this.successTables = successTables; }

    public Integer getFailedTablesCount() { return failedTablesCount; }
    public void setFailedTablesCount(Integer failedTablesCount) { this.failedTablesCount = failedTablesCount; }

    public String getFailedTables() { return failedTables; }
    public void setFailedTables(String failedTables) { this.failedTables = failedTables; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getReportPath() { return reportPath; }
    public void setReportPath(String reportPath) { this.reportPath = reportPath; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
