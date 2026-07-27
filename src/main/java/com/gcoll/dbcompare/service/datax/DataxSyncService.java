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
 */
@Service
public class DataxSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataxSyncService.class);

    private final DataSourceService dataSourceService;
    private final AppSettingsService appSettingsService;

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

        String splitPk = (primaryKeys != null && !primaryKeys.isEmpty()) ? primaryKeys.get(0) : null;
        int channel = settings.getDataxChannel();
        int batchSize = task.getBatchSize() != null ? Math.min(task.getBatchSize(), 4096) : 2048;
        String clearMode = DataxClearMode.normalize(settings.getDataxClearMode());
        // 仅 DATAX_DELETE / DATAX_TRUNCATE 写入作业 preSql；应用侧已清表时作业不再清
        String jobClearMode = DataxClearMode.isDataxSideClear(clearMode) ? clearMode : DataxClearMode.NONE;
        String writeMode = DataxWriteMode.normalize(settings.getDataxWriteMode());

        Map<String, Object> job = DataxJobBuilder.buildMysqlFullCopyJob(
                srcJdbc, srcUser, srcPass, srcTableRef,
                tgtJdbc, tgtUser, tgtPass, tgtTableRef,
                splitPk, channel, batchSize, jobClearMode, writeMode);

        Path jobDir = Paths.get("data", "datax-jobs", String.valueOf(task.getId()));
        Path jobFile = DataxJobBuilder.writeJobFile(jobDir, sanitize(tableName) + ".json", job);
        String preSql = DataxClearMode.buildPreSql(tgtTableRef, jobClearMode);
        log.info("DataX 作业已写入: {} srcJdbc库={} tgtJdbc库={} table={}->{} jobClear={} preSql={}",
                jobFile.toAbsolutePath(), srcCatalog, tgtCatalog, srcTableRef, tgtTableRef,
                jobClearMode, preSql);
        if (logLine != null) {
            logLine.accept("DataX 作业: " + jobFile.toAbsolutePath()
                    + " channel=" + channel
                    + " splitPk=" + (splitPk != null ? splitPk : "(无)")
                    + " writeMode=" + writeMode
                    + " 表=" + srcTableRef + "→" + tgtTableRef
                    + " 清表=" + DataxClearMode.displayName(clearMode)
                    + (preSql != null ? (" preSql=" + preSql) : "（作业内无 preSql）"));
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
        // DataX 不回传精确行数；调用方用 -1 表示「由 DataX 完成」
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
        // 已带库名则不再套一层
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
