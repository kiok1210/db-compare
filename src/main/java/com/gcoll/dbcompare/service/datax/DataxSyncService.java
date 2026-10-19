package com.gcoll.dbcompare.service.datax;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.service.AppSettingsService;
import com.gcoll.dbcompare.service.DataSourceService;
import com.gcoll.dbcompare.util.JdbcConnectionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 按同步任务表生成 DataX 作业并拉起本机 DataX 进程。
 * <p>
 * 全局限流：同时运行的 DataX 进程数受 {@link AppSettings#getDataxMaxConcurrent()} 约束，
 * 通道数按并发均分全局预算，避免表级并行叠加撑爆内存。
 */
@Service
public class DataxSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataxSyncService.class);

    private final DataSourceService dataSourceService;
    private final AppSettingsService appSettingsService;
    private final DataxConcurrency concurrency = new DataxConcurrency();

    public DataxSyncService(DataSourceService dataSourceService, AppSettingsService appSettingsService) {
        this.dataSourceService = dataSourceService;
        this.appSettingsService = appSettingsService;
    }

    public long syncTable(SyncTask task,
                          DataSourceConfig sourceConfig,
                          DataSourceConfig targetConfig,
                          String srcCatalog,
                          String tgtCatalog,
                          String tableName,
                          List<String> primaryKeys,
                          long targetClearRows,
                          long sourceRowCount,
                          BooleanSupplier cancelCheck,
                          Consumer<String> logLine,
                          Runnable pollHeartbeat) throws Exception {
        if (sourceConfig.getDbType() != DatabaseType.MYSQL && sourceConfig.getDbType() != DatabaseType.GOLDENDB) {
            throw new IllegalArgumentException("DataX 模式目前仅支持 MySQL / GoldenDB 源库");
        }
        if (targetConfig.getDbType() != DatabaseType.MYSQL && targetConfig.getDbType() != DatabaseType.GOLDENDB) {
            throw new IllegalArgumentException("DataX 模式目前仅支持 MySQL / GoldenDB 目标库");
        }

        AppSettings settings = appSettingsService.getSettings();
        Path dataxPy = DataxProcessRunner.resolveDataxPy(settings.getDataxHome());
        int maxConcurrent = DataxConcurrency.clampConcurrent(settings.getDataxMaxConcurrent());
        int configuredChannel = DataxConcurrency.clampChannel(settings.getDataxChannel());
        int channel = DataxConcurrency.effectiveChannel(configuredChannel, maxConcurrent);

        if (logLine != null && concurrency.getActive() >= maxConcurrent) {
            logLine.accept("DataX 槽位已满（并发上限 " + maxConcurrent
                    + "），排队等待中… 活动进程=" + concurrency.getActive());
        }
        final java.util.concurrent.atomic.AtomicLong lastWaitLogMs =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        concurrency.acquire(maxConcurrent, cancelCheck, () -> {
            if (pollHeartbeat != null) {
                pollHeartbeat.run();
            }
            if (logLine != null) {
                long now = System.currentTimeMillis();
                if (now - lastWaitLogMs.get() >= 15_000L) {
                    lastWaitLogMs.set(now);
                    logLine.accept("【心跳】仍在运行 · 排队等待 DataX 槽位（活动进程="
                            + concurrency.getActive() + "/" + maxConcurrent + "）");
                }
            }
        });
        try {
            return runJob(task, sourceConfig, targetConfig, srcCatalog, tgtCatalog, tableName,
                    primaryKeys, settings, dataxPy, channel, maxConcurrent, configuredChannel,
                    targetClearRows, sourceRowCount, cancelCheck, logLine, pollHeartbeat);
        } finally {
            concurrency.release();
        }
    }

    /**
     * @param estimatedRows 兼容旧调用：同时当作清表行数与源行数参考
     */
    public long syncTable(SyncTask task,
                          DataSourceConfig sourceConfig,
                          DataSourceConfig targetConfig,
                          String srcCatalog,
                          String tgtCatalog,
                          String tableName,
                          List<String> primaryKeys,
                          long estimatedRows,
                          BooleanSupplier cancelCheck,
                          Consumer<String> logLine,
                          Runnable pollHeartbeat) throws Exception {
        return syncTable(task, sourceConfig, targetConfig, srcCatalog, tgtCatalog, tableName,
                primaryKeys, estimatedRows, estimatedRows, cancelCheck, logLine, pollHeartbeat);
    }

    /** @deprecated 请传 targetClearRows / sourceRowCount */
    @Deprecated
    public long syncTable(SyncTask task,
                          DataSourceConfig sourceConfig,
                          DataSourceConfig targetConfig,
                          String srcCatalog,
                          String tgtCatalog,
                          String tableName,
                          List<String> primaryKeys,
                          BooleanSupplier cancelCheck,
                          Consumer<String> logLine,
                          Runnable pollHeartbeat) throws Exception {
        return syncTable(task, sourceConfig, targetConfig, srcCatalog, tgtCatalog, tableName,
                primaryKeys, -1L, -1L, cancelCheck, logLine, pollHeartbeat);
    }

    private long runJob(SyncTask task,
                        DataSourceConfig sourceConfig,
                        DataSourceConfig targetConfig,
                        String srcCatalog,
                        String tgtCatalog,
                        String tableName,
                        List<String> primaryKeys,
                        AppSettings settings,
                        Path dataxPy,
                        int channel,
                        int maxConcurrent,
                        int configuredChannel,
                        long targetClearRows,
                        long sourceRowCount,
                        BooleanSupplier cancelCheck,
                        Consumer<String> logLine,
                        Runnable pollHeartbeat) throws Exception {
        String srcJdbc = ensureCatalogInUrl(
                JdbcConnectionHelper.normalizeJdbcUrl(sourceConfig.getUrl(), sourceConfig.getDbType()),
                srcCatalog, sourceConfig.getDbType());
        String tgtJdbc = ensureCatalogInUrl(
                JdbcConnectionHelper.normalizeJdbcUrl(targetConfig.getUrl(), targetConfig.getDbType()),
                tgtCatalog, targetConfig.getDbType());
        String srcTableRef = qualifyMysqlTable(srcCatalog, tableName);
        String tgtTableRef = qualifyMysqlTable(tgtCatalog, tableName);

        String srcUser = sourceConfig.getUsername();
        String tgtUser = targetConfig.getUsername();
        String srcPass = dataSourceService.resolvePasswordPublic(sourceConfig);
        String tgtPass = dataSourceService.resolvePasswordPublic(targetConfig);

        String splitPk = null;
        if (channel > 1 && primaryKeys != null && !primaryKeys.isEmpty()) {
            splitPk = primaryKeys.get(0);
        }
        int batchSize = task.getBatchSize() != null ? Math.min(task.getBatchSize(), 4096) : 2048;
        String clearMode = DataxClearMode.resolve(
                task != null ? task.getDataxClearMode() : null, settings.getDataxClearMode());
        String jobClearMode = DataxClearMode.isDataxSideClear(clearMode) ? clearMode : DataxClearMode.NONE;
        String writeMode = DataxWriteMode.normalize(settings.getDataxWriteMode());
        int deleteLimit = settings.getSyncDeleteBatchSize() > 0
                ? settings.getSyncDeleteBatchSize() : DataxClearMode.DEFAULT_DELETE_LIMIT;

        // preSql 批次数只看目标 COUNT；reader 仍全表读源，不受目标行数限制
        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                srcJdbc, srcUser, srcPass, srcTableRef,
                tgtJdbc, tgtUser, tgtPass, tgtTableRef,
                splitPk, channel, batchSize, jobClearMode, writeMode,
                targetClearRows, deleteLimit);

        Path jobDir = Paths.get("data", "datax-jobs", String.valueOf(task.getId()));
        Path jobFile = DataxJobBuilder.writeJobFile(jobDir, sanitize(tableName) + ".json", job);
        List<String> preSqls = DataxClearMode.buildPreSqlList(
                tgtTableRef, jobClearMode, targetClearRows, deleteLimit);
        log.info("DataX 作业已写入: {} channel={}/{} maxConcurrent={} active={} table={}->{} "
                        + "preSqlBatches={} targetClearRows={} sourceRowCount={}",
                jobFile.toAbsolutePath(), channel, configuredChannel, maxConcurrent,
                concurrency.getActive(), srcTableRef, tgtTableRef, preSqls.size(),
                targetClearRows, sourceRowCount);
        if (logLine != null) {
            String clearHint = DataxClearMode.displayName(clearMode);
            if (DataxClearMode.DATAX_DELETE.equals(DataxClearMode.normalize(jobClearMode))) {
                clearHint += "，目标COUNT=" + (targetClearRows < 0 ? "未知" : targetClearRows)
                        + " → LIMIT=" + deleteLimit + " × " + preSqls.size() + " 批"
                        + "；写入基准源COUNT=" + (sourceRowCount < 0 ? "未知" : sourceRowCount);
            }
            logLine.accept("DataX 作业: " + jobFile.toAbsolutePath()
                    + " channel=" + channel + (channel != configuredChannel
                    ? ("（配置 " + configuredChannel + "，按并发上限 " + maxConcurrent + " 下调）") : "")
                    + " 并发槽=" + concurrency.getActive() + "/" + maxConcurrent
                    + " splitPk=" + (splitPk != null ? splitPk : "(无)")
                    + " writeMode=" + writeMode
                    + " 表=" + srcTableRef + "→" + tgtTableRef
                    + " 清表=" + clearHint
                    + (preSqls.isEmpty() ? "（作业内无 preSql）"
                    : (" preSql[0]=" + preSqls.get(0)
                    + (preSqls.size() > 1 ? (" …共" + preSqls.size() + "条") : ""))));
        }

        int code = DataxProcessRunner.run(dataxPy, jobFile, cancelCheck, line -> {
            if (logLine != null && line != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    logLine.accept(t.length() > 500 ? t.substring(0, 500) + "..." : t);
                }
            }
        }, pollHeartbeat);
        if (code != 0) {
            throw new IllegalStateException("DataX 退出码=" + code + "，作业=" + jobFile.toAbsolutePath());
        }
        return -1L;
    }

    /**
     * 强制 JDBC URL 使用表所在 catalog。
     * 修复：host:3306?params（无斜杠）旧逻辑会拼成非法 URL；以及无库名导致 DataX No database selected。
     */
    static String ensureCatalogInUrl(String jdbcUrl, String catalog, DatabaseType dbType) {
        if (jdbcUrl == null || catalog == null || catalog.trim().isEmpty()) {
            return jdbcUrl;
        }
        if (dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB) {
            return jdbcUrl;
        }
        return JdbcConnectionHelper.forceDatabaseInMysqlUrl(jdbcUrl, catalog.trim());
    }

    /** DataX 表名带库：gcoll_aux.customer_payments（双保险，避免连接未选库） */
    static String qualifyMysqlTable(String catalog, String tableName) {
        String t = tableName == null ? "" : tableName.trim();
        if (t.isEmpty()) {
            return t;
        }
        if (t.indexOf('.') >= 0) {
            return t;
        }
        String c = catalog == null ? "" : catalog.trim();
        if (c.isEmpty()) {
            return t;
        }
        return c + "." + t;
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "table";
        }
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
