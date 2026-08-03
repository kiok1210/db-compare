package com.gcoll.dbcompare.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 应用全局参数（持久化在 AppMeta，供新建任务默认填充）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    /** 同步表级并行/连接数全局默认（1–20；任务确认页默认优先按表数） */
    private int syncParallelThreads = 8;
    /** 同步批量 INSERT 行数 */
    private int syncBatchSize = 20000;
    /** 同步 DML 清表每批 DELETE 行数 */
    private int syncDeleteBatchSize = 50000;
    /** 新建同步默认模式：FULL_DML / FULL_DDL / INCREMENTAL */
    private String syncDefaultMode = SyncTask.MODE_FULL_DML;
    /** 比对抽样/数据比对批次大小 */
    private int compareBatchSize = 1000;
    /** 本机 DataX 安装根目录（含 bin/datax.py）；DATAX 同步模式必填 */
    private String dataxHome = "";
    /** DataX 通道数（1–16，默认 4；与并发进程共同受全局预算约束） */
    private int dataxChannel = 4;
    /**
     * 同时运行的 DataX 进程上限（1–3，默认 1）。
     * 表级并行较高时，多余表会排队等槽位，避免多 JVM×多通道撑爆内存。
     */
    private int dataxMaxConcurrent = 1;
    /**
     * DataX 清表策略：AUTO / TRUNCATE / DELETE / DATAX_DELETE / DATAX_TRUNCATE / NONE。
     */
    private String dataxClearMode = "AUTO";
    /**
     * DataX mysqlwriter 写入模式：insert / replace。
     * replace 可不清表覆盖已有主键行（不删除目标多余行）。
     */
    private String dataxWriteMode = "insert";

    public int getSyncParallelThreads() { return syncParallelThreads; }
    public void setSyncParallelThreads(int syncParallelThreads) {
        this.syncParallelThreads = syncParallelThreads;
    }

    public int getSyncBatchSize() { return syncBatchSize; }
    public void setSyncBatchSize(int syncBatchSize) {
        this.syncBatchSize = syncBatchSize;
    }

    public int getSyncDeleteBatchSize() { return syncDeleteBatchSize; }
    public void setSyncDeleteBatchSize(int syncDeleteBatchSize) {
        this.syncDeleteBatchSize = syncDeleteBatchSize;
    }

    public String getSyncDefaultMode() { return syncDefaultMode; }
    public void setSyncDefaultMode(String syncDefaultMode) {
        this.syncDefaultMode = syncDefaultMode;
    }

    public int getCompareBatchSize() { return compareBatchSize; }
    public void setCompareBatchSize(int compareBatchSize) {
        this.compareBatchSize = compareBatchSize;
    }

    public String getDataxHome() { return dataxHome; }
    public void setDataxHome(String dataxHome) {
        this.dataxHome = dataxHome != null ? dataxHome.trim() : "";
    }

    public int getDataxChannel() { return dataxChannel; }
    public void setDataxChannel(int dataxChannel) {
        this.dataxChannel = dataxChannel;
    }

    public int getDataxMaxConcurrent() { return dataxMaxConcurrent; }
    public void setDataxMaxConcurrent(int dataxMaxConcurrent) {
        this.dataxMaxConcurrent = dataxMaxConcurrent;
    }

    public String getDataxClearMode() { return dataxClearMode; }
    public void setDataxClearMode(String dataxClearMode) {
        this.dataxClearMode = dataxClearMode;
    }

    public String getDataxWriteMode() { return dataxWriteMode; }
    public void setDataxWriteMode(String dataxWriteMode) {
        this.dataxWriteMode = dataxWriteMode;
    }
}
