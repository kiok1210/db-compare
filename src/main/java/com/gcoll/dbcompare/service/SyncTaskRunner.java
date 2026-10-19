package com.gcoll.dbcompare.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcoll.dbcompare.util.ProgressTextHelper;
import com.gcoll.dbcompare.util.SchemaMappingHelper;
import com.gcoll.dbcompare.util.SyncDataSyncHelper;
import com.gcoll.dbcompare.util.SyncUpsertHelper;
import com.gcoll.dbcompare.util.TableFilterHelper;
import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.SyncTaskRepository;
import com.gcoll.dbcompare.service.datax.DataxClearMode;
import com.gcoll.dbcompare.service.datax.DataxLogMonitor;
import com.gcoll.dbcompare.service.datax.DataxSyncService;
import com.gcoll.dbcompare.service.datax.DataxWriteMode;
import com.gcoll.dbcompare.service.datax.SyncHybridRouter;
import com.gcoll.dbcompare.sql.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
public class SyncTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskRunner.class);
    private static final int FALLBACK_BATCH_SIZE = 20000;
    /** DML 全量清表：分批 DELETE 的兜底每批行数（优先用全局参数） */
    private static final int FALLBACK_DELETE_BATCH_SIZE = 50000;
    /** 单次 executeBatch 上限：GoldenDB/MySQL rewrite 多值包过大易协议损坏/PacketTooBig */
    private static final int MAX_EXECUTE_BATCH_CHUNK = 500;
    /** 内存中积压待写行上限：到量即刷，避免一次攒过大再切块 */
    private static final int MAX_INSERT_PENDING_ROWS = 1000;
    /** 关闭周期性「刷写 N 批就重借」：正常归还复用池连接，仅损坏时剔除 */
    private static final int INSERT_TARGET_RECYCLE_EVERY_FLUSHES = Integer.MAX_VALUE;
    /** 写入批次最大重试次数 */
    private static final int INSERT_FLUSH_MAX_ATTEMPTS = 5;
    /** 主键分块翻页大小（避免千万级单次流式 ResultSet + 目标协议损坏互相拖死） */
    private static final int KEYSET_PAGE_ROWS = 5000;
    /** 差异同步按主键 IN 查询目标/源时的每批键数（过大易 PacketTooBig） */
    private static final int DATA_SYNC_PK_IN_CHUNK = 500;
    /** DataX 通道积压批次数（读写下并行时的内存上限≈页数×页行数） */
    private static final int STREAM_CHANNEL_CAPACITY = SyncRecordChannel.DEFAULT_CAPACITY;
    /** 关闭翻页过程中周期性重借整池连接 */
    private static final int KEYSET_RECYCLE_EVERY_PAGES = Integer.MAX_VALUE;
    /** 单页写入失败后整页重试次数 */
    private static final int KEYSET_PAGE_MAX_ATTEMPTS = 3;
    /** 常见会话/代理 SELECT 截断上限，命中时告警 */
    private static final long[] SUSPICIOUS_SELECT_CAPS = {20_000L, 50_000L, 100_000L, 200_000L, 500_000L};
    /** 进度心跳：至少间隔秒数打一条日志，避免长时间静默 */
    private static final int PROGRESS_LOG_INTERVAL_SEC = 5;
    /** Future 收集轮询间隔 */
    private static final long FUTURE_POLL_MS = 2_000L;
    /**
     * 长清表期间定期归还并重借目标连接，避免 JumpServer/防火墙空闲掐断，
     * 以及会话 wait_timeout（截图中约 2.5h）把「看起来还握着」的连接判死。
     */
    private static final long LONG_PHASE_RECYCLE_MS = 10 * 60 * 1000L;
    private static final String STAGING_NEW_SUFFIX = "__dbsync_new";
    private static final String STAGING_OLD_SUFFIX = "__dbsync_old";
    /** 工作线程持有的「并行中表」集合引用，用于进度展示对齐 */
    private static final ThreadLocal<Set<String>> TL_RUNNING = new ThreadLocal<>();
    /** 当前表同步看门狗（主线程与工作线程共享同一实例） */
    private static final ThreadLocal<SyncIdleWatchdog> TL_WATCHDOG = new ThreadLocal<>();
    /** 当前工作线程对应的表标签 */
    private static final ThreadLocal<String> TL_TABLE_TAG = new ThreadLocal<>();

    /** 表结果状态：未同步（未清理）/ 清理失败 / 同步失败 / 成功 */
    public static final String OUT_NOT_CLEARED = "NOT_CLEARED";
    public static final String OUT_CLEAR_FAILED = "CLEAR_FAILED";
    public static final String OUT_SYNC_FAILED = "SYNC_FAILED";
    public static final String OUT_SUCCESS = "SUCCESS";

    private final SyncTaskRepository syncTaskRepository;
    private final DataSourceService dataSourceService;
    private final ReportService reportService;
    private final AppSettingsService appSettingsService;
    private final SyncProgressLogBuffer progressLogBuffer;
    private final DataxSyncService dataxSyncService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<Long> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    /**
     * 同一目标表清表互斥：并行任务若对同一物理表同时 {@code DELETE … LIMIT n}（无 ORDER BY），
     * 会争抢同一批行锁 → 1205。单连接串行分批无此问题。
     */
    private final ConcurrentHashMap<String, ReentrantLock> targetClearLocks = new ConcurrentHashMap<>();
    /** 表级 0–100 进度（并行时汇总驱动圆环；DataX 清表/写入阶段细粒度更新） */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, AtomicInteger>> liveTableProgressPct =
            new ConcurrentHashMap<>();
    /** DataX 清表阶段按约 4.5 万行/秒估算进度（preSql 无原生百分比） */
    private static final long ASSUMED_DATAX_DELETE_ROWS_PER_SEC = 45_000L;

    public SyncTaskRunner(SyncTaskRepository syncTaskRepository, DataSourceService dataSourceService,
                          ReportService reportService, AppSettingsService appSettingsService,
                          SyncProgressLogBuffer progressLogBuffer, DataxSyncService dataxSyncService) {
        this.syncTaskRepository = syncTaskRepository;
        this.dataSourceService = dataSourceService;
        this.reportService = reportService;
        this.appSettingsService = appSettingsService;
        this.progressLogBuffer = progressLogBuffer;
        this.dataxSyncService = dataxSyncService;
    }

    /** 请求取消：进行中的清表/写入会尽快中止，并记为清理失败或未清理 */
    public void requestCancel(Long taskId) {
        if (taskId != null) {
            cancelledTaskIds.add(taskId);
        }
    }

    @Async("syncTaskExecutor")
    public void run(Long taskId) {
        SyncTask task = syncTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.error("同步任务不存在: taskId={}", taskId);
            return;
        }

        cancelledTaskIds.remove(taskId);
        progressLogBuffer.clear(taskId);
        task.setStatus(SyncTask.STATUS_RUNNING);
        task.setStartTime(LocalDateTime.now());
        syncTaskRepository.save(task);
        uiLog(taskId, "info", "同步任务启动 taskId=" + taskId);

        DataSourceConfig sourceConfig = dataSourceService.getDataSourceById(task.getSourceDataSourceId());
        DataSourceConfig targetConfig = dataSourceService.getDataSourceById(task.getTargetDataSourceId());

        if (sourceConfig == null || targetConfig == null) {
            task.setStatus(SyncTask.STATUS_FAILED);
            task.setErrorMessage("数据源配置不存在");
            task.setEndTime(LocalDateTime.now());
            syncTaskRepository.save(task);
            return;
        }

        DatabaseType sourceDbType = sourceConfig.getDbType();
        DatabaseType targetDbType = targetConfig.getDbType();
        final String sourceSchema = task.getSourceSchema() == null ? null : task.getSourceSchema().trim();
        final String targetSchema = task.getTargetSchema() == null ? null : task.getTargetSchema().trim();
        Map<String, String> schemaMap = SchemaMappingHelper.parseSchemaMapping(task.getSchemaMapping());
        boolean isMultiSource = sourceSchema == null || sourceSchema.isEmpty() || sourceSchema.contains(",");
        boolean isMultiTarget = targetSchema == null || targetSchema.isEmpty() || targetSchema.contains(",");

        int requestedParallel = SyncTask.clampParallelThreads(task.getParallelThreads());
        final int parallel;
        if (targetDbType == DatabaseType.GOLDENDB
                && requestedParallel > SyncIdleWatchdog.GOLDENDB_MAX_PARALLEL) {
            parallel = SyncIdleWatchdog.GOLDENDB_MAX_PARALLEL;
            uiLog(taskId, "warn", "GoldenDB 目标：表级并行由 " + requestedParallel + " 调整为 " + parallel
                    + "（降低协议异常与等连接无心跳误杀；可在参数中自行设更低）");
            log.warn("GoldenDB 目标并行过高，已从 {} 调整为 {}", requestedParallel, parallel);
        } else {
            parallel = requestedParallel;
        }
        task.setParallelThreads(parallel);

        String srcDsLabel = dsLabel(sourceConfig);
        String tgtDsLabel = dsLabel(targetConfig);
        log.info("============================================================");
        log.info("【数据流向】只读源 → 写入目标（未对调）");
        log.info("  源库(SELECT)     = {} | url={}", srcDsLabel, safeUrl(sourceConfig.getUrl()));
        log.info("  目标库(DELETE/INSERT) = {} | url={}", tgtDsLabel, safeUrl(targetConfig.getUrl()));
        log.info("  源库范围={}, 目标库范围={}, 映射={}", sourceSchema, targetSchema, task.getSchemaMapping());
        log.info("============================================================");
        uiLog(taskId, "info", "【数据流向】源(只读SELECT) → 目标(DELETE/INSERT)，未对调");
        uiLog(taskId, "info", "源=" + srcDsLabel + " | 目标=" + tgtDsLabel);
        if (targetDbType == DatabaseType.MYSQL || targetDbType == DatabaseType.GOLDENDB) {
            uiLog(taskId, "info", "目标写入已启用 JDBC rewriteBatchedStatements（多值批量 INSERT，预期可达万级行/秒）");
        }

        // 任务级共享连接池：一对 Hikari（源/目标），表间借还复用；任务结束 try-with-resources 必关池
        try (SyncJdbcPools pools = dataSourceService.createSyncPools(
                sourceConfig, targetConfig, parallel, task.isDataSyncMode())) {
            uiLog(taskId, "info", "已创建任务连接池 " + pools.describeStats()
                    + "（表间复用，不按表新建；任务结束自动关闭）");

            Map<String, String> tgtTableCatalogMap;
            List<String> tables;
            try (Connection sourceConn = pools.borrowSource(sourceSchema, isMultiSource);
                 Connection targetConn = pools.borrowTarget(targetSchema, isMultiTarget, false)) {
                tgtTableCatalogMap = isMultiTarget
                        ? SchemaMappingHelper.buildTargetTableCatalogMap(targetConn, targetSchema, targetDbType)
                        : Collections.emptyMap();
                tables = TableFilterHelper.resolveTables(
                        sourceConn, sourceSchema, sourceDbType,
                        task.getIncludeTables(), task.getExcludeTables());
            }
            if (tables.isEmpty()
                    && task.getIncludeTables() != null
                    && !task.getIncludeTables().trim().isEmpty()) {
                // 兜底：显式 include 未被枚举命中时仍按 include 键执行（避免 SUCCESS 总表=0）
                tables = TableFilterHelper.splitPatterns(task.getIncludeTables()).stream()
                        .map(TableFilterHelper::normalizeTableKey)
                        .filter(s -> !s.isEmpty() && !"?".equals(s) && !"unknown".equalsIgnoreCase(s))
                        .distinct()
                        .collect(Collectors.toList());
                log.warn("resolveTables 为空，回退为 includeTables 显式列表 ({} 张): {}",
                        tables.size(), tables);
                uiLog(taskId, "warn", "库表枚举未命中 include，已回退为显式表列表 " + tables.size() + " 张");
            }
            if (tables.isEmpty()) {
                task.setTotalTables(0);
                task.setProcessedTables(0);
                task.setSuccessTables(0);
                task.setFailedTablesCount(0);
                task.setStatus(SyncTask.STATUS_FAILED);
                task.setErrorMessage("未找到可同步的表（请检查库范围、includeTables 与源库是否可见）");
                task.setCurrentTable("★★★ 同步任务结束 ★★★ FAILED（总表=0）");
                task.setEndTime(LocalDateTime.now());
                task.setProgress(100);
                syncTaskRepository.save(task);
                uiLog(taskId, "error", "【同步任务结束】未找到可同步的表，status=FAILED");
                return;
            }
            task.setTotalTables(tables.size());
            syncTaskRepository.save(task);

            ExecutorService executor = Executors.newFixedThreadPool(parallel);
            List<Future<TableSyncResult>> futures = new ArrayList<>();
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger liveSuccess = new AtomicInteger(0);
            AtomicInteger liveFailed = new AtomicInteger(0);
            AtomicInteger lastSaveTime = new AtomicInteger((int) (System.currentTimeMillis() / 1000));
            Set<String> runningTables = ConcurrentHashMap.newKeySet();
            final SyncIdleWatchdog watchdog = SyncIdleWatchdog.forTarget(targetDbType);
            TL_WATCHDOG.set(watchdog);
            watchdog.touch();
            final int syncBatchSize = task.getBatchSize() != null ? task.getBatchSize() : FALLBACK_BATCH_SIZE;
            final String syncMode = SyncTask.normalizeMode(task.getSyncMode());
            final boolean incremental = task.isIncrementalMode();
            final boolean pkUpsert = task.isPkUpsertMode();
            final boolean dataSync = task.isDataSyncMode();
            final boolean noClear = task.isNoClearMode();
            final boolean fullDdl = task.isFullDdlMode();
            // DATA_TRANSFER 走下方 DML 全量（清表+INSERT），与 FULL_DML 同路径
            long taskStartMs = System.currentTimeMillis();
            log.info("============================================================");
            log.info("【同步任务开始】 taskId={}, 总表数={}, 表级并行={}, 连接池(max≈{}), 模式={}, 策略={}",
                    taskId, tables.size(), parallel, pools.getPoolSize(), syncMode, describeStrategy(syncMode));
            log.info("说明: 任务级共享连接池（连接按线程借还，不跨线程共享 Connection）");
            log.info("看门狗: 普通表无心跳>{}s 跳过；超大表(≥{}行)放宽至{}s；整任务无心跳>{}s（有超大表时{}s）失败",
                    watchdog.configuredTableIdleMs() / 1000,
                    SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD,
                    SyncIdleWatchdog.LARGE_TABLE_IDLE_MS / 1000,
                    watchdog.configuredTaskIdleMs() / 1000,
                    SyncIdleWatchdog.LARGE_TASK_IDLE_MS / 1000);
            log.info("============================================================");
            uiLog(taskId, "info", "【同步任务开始】 总表=" + tables.size()
                    + " 并行=" + parallel + " 模式=" + syncMode + " 策略=" + describeStrategy(syncMode));
            uiLog(taskId, "info", "说明: " + describeStrategy(syncMode));
            uiLog(taskId, "info", "看门狗: 普通表 " + (watchdog.configuredTableIdleMs() / 1000)
                    + "s 无心跳跳过；超大表(≥" + SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD
                    + "行) " + (SyncIdleWatchdog.LARGE_TABLE_IDLE_MS / 1000)
                    + "s；整任务 " + (watchdog.configuredTaskIdleMs() / 1000)
                    + "s（有超大表 " + (SyncIdleWatchdog.LARGE_TASK_IDLE_MS / 1000) + "s）无心跳失败；"
                    + "等池/写入进度均带【心跳】");

            final int tableTotal = tables.size();
            // 与 futures 一一对应，供 Future#get 异常时仍能保留真实表名（避免写成 "?" 导致重同步失败）
            List<String> futureTableKeys = new ArrayList<>(tableTotal);
            List<String> futureDisplayNames = new ArrayList<>(tableTotal);
            List<String> futureTableTags = new ArrayList<>(tableTotal);
            ConcurrentHashMap<String, AtomicInteger> tablePctMap = new ConcurrentHashMap<>();
            liveTableProgressPct.put(taskId, tablePctMap);
            for (int ti = 0; ti < tableTotal; ti++) {
                String tableName = tables.get(ti);
                SchemaMappingHelper.CatalogPair catalogs = SchemaMappingHelper.resolveCatalogs(
                        tableName, sourceSchema, targetSchema,
                        isMultiSource, isMultiTarget, schemaMap, tgtTableCatalogMap);
                final String fSrcCat = firstNonEmpty(catalogs.srcCat,
                        (!isMultiSource ? sourceSchema : null));
                final String fTgtCat = firstNonEmpty(catalogs.tgtCat,
                        (!isMultiTarget ? targetSchema : null));
                final String fPureTableName = catalogs.pureTableName;
                final String sourceTableKey = tableName; // 保留 schema::table，供失败重同步
                final int tableIndex = ti + 1;
                // 含库名 + 表序号，前后台日志/进度统一使用
                final String tableTag = formatTableTag(tableIndex, tableTotal, fSrcCat, fTgtCat, fPureTableName);
                futureTableKeys.add(sourceTableKey);
                futureDisplayNames.add(formatCatalogTable(fSrcCat, fPureTableName));
                futureTableTags.add(tableTag);
                tablePctMap.put(tableTag, new AtomicInteger(0));

                futures.add(executor.submit(() -> {
                    TableSyncResult result = new TableSyncResult();
                    result.table = sourceTableKey;
                    result.displayName = formatCatalogTable(fSrcCat, fPureTableName);
                    result.tableIndex = tableIndex;
                    result.srcCatalog = fSrcCat;
                    result.tgtCatalog = fTgtCat;
                    WorkConns conns = new WorkConns(pools, sourceSchema, isMultiSource,
                            targetSchema, isMultiTarget, targetDbType, taskId);
                    long tableStart = System.currentTimeMillis();
                    TL_WATCHDOG.set(watchdog);
                    TL_TABLE_TAG.set(tableTag);
                    runningTables.add(tableTag);
                    TL_RUNNING.set(runningTables);
                    watchdog.register(tableTag, conns::discardAll);
                    refreshRunningDetail(task, runningTables, lastSaveTime);
                    log.info("{} 开始同步 (并行中 {}/{})", tableTag, runningTables.size(), parallel);
                    uiLog(taskId, "info", tableTag + " 开始同步 (并行 " + runningTables.size() + "/" + parallel + ")");

                    try {
                        checkCancel(taskId, "prepare", false);
                        String srcQualified = buildQualifiedName(fSrcCat, fPureTableName, sourceDbType);
                        String tgtQualified = buildQualifiedName(fTgtCat, fPureTableName, targetDbType);
                        log.info("{} 源={} → 目标={}", tableTag, srcQualified, tgtQualified);
                        uiLog(taskId, "info", tableTag + " 源=" + srcQualified + " → 目标=" + tgtQualified);
                        uiLog(taskId, "info", tableTag + " 说明: SELECT 只打源库；写操作只打目标库");

                        // 短借源连接读列后立即归还，清表期间不占源池连接
                        result.phase = "meta";
                        updateTaskDetail(task, lastSaveTime, tableTag + " · 读取列信息");
                        conns.borrowSource();
                        List<String> columns = getColumnNames(conns.src, fSrcCat, fPureTableName, sourceDbType);
                        // 始终读主键：全量/增量 INSERT 千万级表走主键分块翻页，避免长流式 ResultSet
                        List<String> primaryKeys = getPrimaryKeys(conns.src, fSrcCat, fPureTableName, sourceDbType);
                        long estimatedRows = estimateTableRows(conns.src, fSrcCat, fPureTableName, sourceDbType);
                        conns.releaseSource();
                        maybeMarkLargeTable(tableTag, estimatedRows, task,
                                dataSync ? SyncIdleWatchdog.DATA_SYNC_HEAVY_ROW_THRESHOLD
                                        : SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD);
                        if (columns.isEmpty()) {
                            result.error = "源表无列信息";
                            result.status = OUT_NOT_CLEARED;
                            result.phase = "meta";
                        } else {
                            long rowCount;
                            if (dataSync) {
                                result.phase = "sync";
                                checkCancel(taskId, "sync", false);
                                if (primaryKeys == null || primaryKeys.isEmpty()) {
                                    throw new SQLException("数据同步需要主键，表 " + fPureTableName + " 无主键，请改用数据传输或全量模式");
                                }
                                conns.borrowSource();
                                conns.borrowTarget(true);
                                conns.borrowTargetRead();
                                updateTaskDetail(task, lastSaveTime, tableTag + " · Navicat 式数据同步");
                                log.info("{} 数据同步(DATA_SYNC) pk={}, batchSize={}, estRows={}",
                                        tableTag, primaryKeys, syncBatchSize, estimatedRows);
                                uiLog(taskId, "info", tableTag + " 数据同步: 按主键 INSERT/UPDATE/DELETE（类 Navicat） pk="
                                        + String.join(",", primaryKeys));
                                rowCount = navicatDataSync(conns, srcQualified, tgtQualified,
                                        columns, primaryKeys, sourceDbType, targetDbType, syncBatchSize,
                                        tableTag, task, lastSaveTime);
                            } else if (pkUpsert) {
                                result.phase = "upsert";
                                checkCancel(taskId, "upsert", false);
                                if (primaryKeys == null || primaryKeys.isEmpty()) {
                                    throw new SQLException("增量合并需要主键，表 " + fPureTableName + " 无主键，请改用增量插入或全量模式");
                                }
                                conns.borrowSource();
                                conns.borrowTarget(true);
                                updateTaskDetail(task, lastSaveTime, tableTag + " · 主键合并写入");
                                log.info("{} 增量合并(PK_UPSERT) pk={}, batchSize={}", tableTag, primaryKeys, syncBatchSize);
                                uiLog(taskId, "info", tableTag + " 增量合并: 按主键 UPDATE 已有行 / INSERT 新行（不清空） pk="
                                        + String.join(",", primaryKeys));
                                rowCount = batchUpsertByPrimaryKey(conns, srcQualified, tgtQualified,
                                        columns, primaryKeys, sourceDbType, targetDbType, syncBatchSize,
                                        tableTag, task, lastSaveTime);
                            } else if (shouldRunDatax(task, estimatedRows, tableTag, taskId)) {
                                result.phase = "datax";
                                checkCancel(taskId, "datax", false);
                                AppSettings dataxSettings = appSettingsService.getSettings();
                                String clearMode = DataxClearMode.resolve(
                                        task.getDataxClearMode(), dataxSettings.getDataxClearMode());
                                String writeMode = DataxWriteMode.normalize(dataxSettings.getDataxWriteMode());
                                updateTaskDetail(task, lastSaveTime, tableTag + " · DataX 同步");
                                log.info("{} DataX 工具同步: table={}, pk={}, clearMode={}, writeMode={}, hybrid={}",
                                        tableTag, fPureTableName, primaryKeys, clearMode, writeMode, task.isHybridMode());
                                uiLog(taskId, "info", tableTag
                                        + " DataX 工具同步（mysqlreader→mysqlwriter），清表策略="
                                        + DataxClearMode.displayName(clearMode)
                                        + "，写入模式=" + DataxWriteMode.displayName(writeMode));

                                if (DataxClearMode.isAppSideClear(clearMode)) {
                                    result.phase = "clear";
                                    result.clearAttempted = true;
                                    checkCancel(taskId, "clear", true);
                                    conns.borrowTarget(true);
                                    if (DataxClearMode.PARALLEL_DELETE.equals(clearMode)) {
                                        int clearPar = SyncTask.clampClearParallel(task.getClearParallelThreads());
                                        if (targetDbType == DatabaseType.GOLDENDB) {
                                            clearPar = Math.min(clearPar, SyncIdleWatchdog.GOLDENDB_MAX_PARALLEL);
                                        }
                                        uiLog(taskId, "info", tableTag
                                                + " DataX 前清表: 应用侧并行分段 DELETE（并行=" + clearPar + "）");
                                        clearTableParallelByPk(conns, fTgtCat, fPureTableName, tableTag,
                                                targetDbType, syncBatchSize, primaryKeys, clearPar,
                                                task, lastSaveTime);
                                    } else if (DataxClearMode.DELETE.equals(clearMode)) {
                                        uiLog(taskId, "info", tableTag + " DataX 前清表: 应用侧分批 DELETE");
                                        clearTable(conns, fTgtCat, fPureTableName, tableTag, targetDbType,
                                                syncBatchSize, task, lastSaveTime);
                                    } else if (DataxClearMode.TRUNCATE.equals(clearMode)) {
                                        uiLog(taskId, "info", tableTag + " DataX 前清表: 应用侧 TRUNCATE");
                                        if (!truncateTable(conns.tgt, fTgtCat, fPureTableName, tableTag,
                                                targetDbType, task, lastSaveTime)) {
                                            throw new SQLException("TRUNCATE 失败且清表策略为仅 TRUNCATE；"
                                                    + "请改任务清表策略为 AUTO、分批 DELETE 或并行分段 DELETE");
                                        }
                                    } else {
                                        // AUTO：TRUNCATE → 失败则 DELETE
                                        uiLog(taskId, "info", tableTag + " DataX 前清表: 尝试应用侧 TRUNCATE");
                                        if (!truncateTable(conns.tgt, fTgtCat, fPureTableName, tableTag,
                                                targetDbType, task, lastSaveTime)) {
                                            uiLog(taskId, "warn", tableTag
                                                    + " TRUNCATE 失败（可能无权限），回退应用侧分批 DELETE");
                                            log.warn("{} TRUNCATE 失败，DataX 前改用分批 DELETE", tableTag);
                                            clearTable(conns, fTgtCat, fPureTableName, tableTag, targetDbType,
                                                    syncBatchSize, task, lastSaveTime);
                                        }
                                    }
                                    conns.recycleAfterLongPhase(tableTag);
                                } else if (DataxClearMode.isDataxSideClear(clearMode)) {
                                    result.clearAttempted = true;
                                    uiLog(taskId, "info", tableTag + " 清表交由 DataX writer.preSql（"
                                            + DataxClearMode.displayName(clearMode)
                                            + "），跳过应用侧慢 DELETE");
                                }

                                // 进度基准：清表用目标 COUNT，写入用源 COUNT（互不混用）
                                long targetClearRows = estimatedRows;
                                long sourceRowCount = estimatedRows;
                                result.phase = "count";
                                checkCancel(taskId, "count", result.clearAttempted);
                                maybeMarkLargeTable(tableTag, Math.max(estimatedRows,
                                        SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD), task);
                                updateTaskDetail(task, lastSaveTime, tableTag + " · COUNT 源/目标");
                                int deleteLimitForPlan = Math.max(DataxClearMode.DEFAULT_DELETE_LIMIT,
                                        appSettingsService.getSettings().getSyncDeleteBatchSize());

                                conns.borrowSource();
                                try {
                                    sourceRowCount = countTableRowsWithHeartbeat(
                                            conns.src, srcQualified, tableTag, taskId, "源表");
                                } finally {
                                    conns.releaseSource();
                                }
                                conns.borrowTarget(false);
                                try {
                                    targetClearRows = countTableRowsWithHeartbeat(
                                            conns.tgt, tgtQualified, tableTag, taskId, "目标表");
                                } finally {
                                    conns.releaseTarget();
                                }

                                if (DataxClearMode.DATAX_DELETE.equals(clearMode)) {
                                    int batches = DataxClearMode.calcBatchCount(targetClearRows, deleteLimitForPlan);
                                    uiLog(taskId, "info", tableTag
                                            + " 进度基准：清表用目标 COUNT=" + targetClearRows
                                            + "（→ DataX preSql DELETE…LIMIT " + deleteLimitForPlan
                                            + " × " + batches + " 批）；写入用源 COUNT=" + sourceRowCount
                                            + "（全量拷源，不按目标行数截断）");
                                } else {
                                    uiLog(taskId, "info", tableTag
                                            + " COUNT 完成：源=" + sourceRowCount
                                            + "，目标=" + targetClearRows
                                            + "（写入进度按源 COUNT）");
                                }

                                result.phase = "datax";
                                checkCancel(taskId, "datax", result.clearAttempted);
                                maybeMarkLargeTable(tableTag, Math.max(Math.max(sourceRowCount, targetClearRows),
                                                SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD),
                                        task);
                                updateTaskDetail(task, lastSaveTime, tableTag + " · DataX 写入");
                                final SyncIdleWatchdog dataxWd = TL_WATCHDOG.get();
                                final String dataxTag = tableTag;
                                final DataxLogMonitor.Snapshot dataxSnap = new DataxLogMonitor.Snapshot();
                                dataxSnap.sourceTotalRows = sourceRowCount;
                                dataxSnap.clearTargetRows = targetClearRows;
                                if (DataxClearMode.DATAX_DELETE.equals(clearMode)) {
                                    dataxSnap.clearBatches = DataxClearMode.calcBatchCount(targetClearRows,
                                            Math.max(DataxClearMode.DEFAULT_DELETE_LIMIT,
                                                    appSettingsService.getSettings().getSyncDeleteBatchSize()));
                                }
                                final AtomicLong lastProgressLogMs = new AtomicLong(0);
                                final AtomicLong lastDataxHeartbeatLogMs = new AtomicLong(0);
                                final AtomicLong lastClearProbeMs = new AtomicLong(0);
                                final long srcCountForHb = sourceRowCount;
                                final long tgtCountForHb = targetClearRows;
                                final long dataxPhaseStartMs = System.currentTimeMillis();
                                final boolean dataxDeleteClear = DataxClearMode.DATAX_DELETE.equals(clearMode);
                                try {
                                    rowCount = dataxSyncService.syncTable(
                                            task, sourceConfig, targetConfig,
                                            fSrcCat, fTgtCat, fPureTableName, primaryKeys,
                                            targetClearRows,
                                            sourceRowCount,
                                            () -> cancelledTaskIds.contains(taskId)
                                                    || (dataxWd != null && (dataxWd.isTaskTimedOut()
                                                    || dataxWd.isTableTimedOut(dataxTag))),
                                            line -> onDataxLogLine(taskId, task, lastSaveTime, dataxWd, dataxTag,
                                                    dataxSnap, lastProgressLogMs, line,
                                                    tableTotal, dataxPhaseStartMs, dataxDeleteClear),
                                            () -> {
                                                if (dataxWd != null) {
                                                    dataxWd.touch(dataxTag);
                                                }
                                                // preSql 清表：每 15s 探一次目标剩余行，避免时间估算卡死在 95%
                                                long now = System.currentTimeMillis();
                                                if (dataxDeleteClear && !dataxSnap.sawProgress
                                                        && tgtCountForHb > 0
                                                        && now - lastClearProbeMs.get() >= 15_000L) {
                                                    lastClearProbeMs.set(now);
                                                    try {
                                                        conns.borrowTarget(false);
                                                        long rem = estimateTableRows(conns.tgt, fTgtCat,
                                                                fPureTableName, targetDbType);
                                                        if (rem >= 0) {
                                                            // 单调不增，避免估算抖动回退进度
                                                            if (dataxSnap.clearRemainingRows < 0
                                                                    || rem < dataxSnap.clearRemainingRows) {
                                                                if (dataxSnap.clearRemainingRows >= 0
                                                                        && dataxSnap.clearRatePrevAtMs > 0) {
                                                                    long dt = now - dataxSnap.clearRatePrevAtMs;
                                                                    long dropped = dataxSnap.clearRemainingRows - rem;
                                                                    if (dt >= 1_000L && dropped > 0) {
                                                                        dataxSnap.clearRecentRowsPerSec =
                                                                                dropped * 1000L / dt;
                                                                    }
                                                                }
                                                                dataxSnap.clearRatePrevRem = rem;
                                                                dataxSnap.clearRatePrevAtMs = now;
                                                                dataxSnap.clearRemainingRows = rem;
                                                            }
                                                        }
                                                    } catch (Exception probeEx) {
                                                        log.debug("{} 清表剩余行探测失败(可忽略): {}",
                                                                dataxTag, probeEx.getMessage());
                                                    } finally {
                                                        try {
                                                            conns.releaseTarget();
                                                        } catch (Exception ignored) {
                                                        }
                                                    }
                                                }
                                                int within = calcDataxWithinTablePct(
                                                        dataxSnap, dataxPhaseStartMs, dataxDeleteClear);
                                                // 进入写入后允许从清表高百分比回落到写入起点(50%)
                                                boolean allowDec = dataxSnap.sawProgress && within <= 55;
                                                publishLiveTableProgress(task, dataxTag, within, tableTotal,
                                                        lastSaveTime, allowDec);
                                                if (now - lastDataxHeartbeatLogMs.get() >= 10_000L) {
                                                    lastDataxHeartbeatLogMs.set(now);
                                                    String hint = formatDataxProgressHint(
                                                            dataxSnap, within, dataxDeleteClear,
                                                            tgtCountForHb, srcCountForHb, dataxPhaseStartMs);
                                                    uiLog(taskId, "info", dataxTag
                                                            + " 【心跳】仍在运行 · DataX " + hint);
                                                    updateTaskDetail(task, lastSaveTime,
                                                            dataxTag + " · DataX " + hint);
                                                }
                                            });
                                    if (rowCount < 0 && dataxSnap.bestRowCount() >= 0) {
                                        rowCount = dataxSnap.bestRowCount();
                                    }
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    throw new SyncAbortException(OUT_SYNC_FAILED, "datax", result.clearAttempted,
                                            "DataX 已取消");
                                }
                            } else if (incremental) {
                                result.phase = "insert";
                                checkCancel(taskId, "insert", false);
                                conns.borrowSource();
                                conns.borrowTarget(true);
                                updateTaskDetail(task, lastSaveTime, tableTag + " · 增量写入");
                                log.info("{} 增量模式(INSERT IGNORE) batchSize={}", tableTag, syncBatchSize);
                                rowCount = batchCopyData(conns, srcQualified, tgtQualified,
                                        columns, primaryKeys, sourceDbType, targetDbType, syncBatchSize,
                                        tableTag, task, lastSaveTime, true, estimatedRows);
                            } else if (fullDdl && supportsMysqlFamily(targetDbType)) {
                                result.phase = "ddl";
                                log.info("{} 全量策略(DDL): CREATE LIKE→INSERT→RENAME，失败则 TRUNCATE→INSERT",
                                        tableTag);
                                conns.borrowTarget(true);
                                rowCount = fullSyncByDdl(conns, sourceDbType, targetDbType,
                                        srcQualified, fTgtCat, fPureTableName, tableTag, columns, primaryKeys,
                                        syncBatchSize, task, lastSaveTime, estimatedRows);
                            } else if (fullDdl) {
                                result.phase = "clear";
                                log.info("{} 全量策略(DDL): TRUNCATE → INSERT", tableTag);
                                conns.borrowTarget(true);
                                checkCancel(taskId, "clear", false);
                                if (!truncateTable(conns.tgt, fTgtCat, fPureTableName, tableTag, targetDbType, task, lastSaveTime)) {
                                    log.warn("{} TRUNCATE 失败，回退 DML 分批 DELETE", tableTag);
                                    clearTable(conns, fTgtCat, fPureTableName, tableTag, targetDbType, syncBatchSize,
                                            task, lastSaveTime);
                                }
                                result.clearAttempted = true;
                                conns.recycleAfterLongPhase(tableTag);
                                result.phase = "insert";
                                updateTaskDetail(task, lastSaveTime, tableTag + " · 开始写入");
                                rowCount = batchCopyData(conns, srcQualified, tgtQualified,
                                        columns, primaryKeys, sourceDbType, targetDbType, syncBatchSize,
                                        tableTag, task, lastSaveTime, false, estimatedRows);
                            } else {
                                result.phase = "clear";
                                String strategyLabel = task.isDataTransferMode()
                                        ? "数据传输(DATA_TRANSFER·类 Navicat)：分批 DELETE → 批量 INSERT"
                                        : "全量策略(DML)：分批 DELETE → 批量 INSERT（清表只占用目标池连接）";
                                log.info("{} {}", tableTag, strategyLabel);
                                if (task.isDataTransferMode()) {
                                    uiLog(taskId, "info", tableTag + " 数据传输: 清空目标表后全量写入（类 Navicat Data Transfer）");
                                }
                                conns.borrowTarget(true);
                                checkCancel(taskId, "clear", false);
                                result.clearAttempted = true;
                                clearTable(conns, fTgtCat, fPureTableName, tableTag, targetDbType, syncBatchSize,
                                        task, lastSaveTime);
                                // 清表后归还并重新借出（池内校验，非每次新建 TCP）
                                conns.recycleAfterLongPhase(tableTag);
                                result.phase = "insert";
                                updateTaskDetail(task, lastSaveTime, tableTag + " · 开始写入");
                                rowCount = batchCopyData(conns, srcQualified, tgtQualified,
                                        columns, primaryKeys, sourceDbType, targetDbType, syncBatchSize,
                                        tableTag, task, lastSaveTime, false, estimatedRows);
                            }
                            result.rowCount = rowCount;
                            result.phase = "done";
                            result.status = OUT_SUCCESS;
                            long elapsedSec = Math.max(1, (System.currentTimeMillis() - tableStart) / 1000);
                            if (rowCount < 0) {
                                log.info("{} ★ 表同步完成 ★ DataX 成功, 耗时={}s（行数见 DataX 日志）",
                                        tableTag, elapsedSec);
                                uiLog(taskId, "success", tableTag + " ★ 表同步完成 ★ DataX 成功, 耗时="
                                        + elapsedSec + "s（行数见上方 DataX 日志）");
                            } else {
                                log.info("{} ★ 表同步完成 ★ 行数={}, 耗时={}s, 约 {} 行/秒",
                                        tableTag, rowCount, elapsedSec, rowCount / elapsedSec);
                                uiLog(taskId, "success", tableTag + " ★ 表同步完成 ★ 行数=" + rowCount
                                        + ", 耗时=" + elapsedSec + "s, 约 " + (rowCount / elapsedSec) + " 行/秒");
                            }
                        }
                    } catch (SyncAbortException e) {
                        result.status = e.outcomeStatus;
                        result.phase = e.phase != null ? e.phase : result.phase;
                        result.error = e.getMessage();
                        result.clearAttempted = result.clearAttempted || e.clearAttempted;
                        log.warn("{} 中止(status={}): {}", tableTag, result.status, result.error);
                    } catch (Exception e) {
                        result.error = toFriendlySyncError(e);
                        if (result.phase == null || "done".equals(result.phase)) {
                            result.phase = "error";
                        }
                        result.status = resolveFailureStatus(result.phase, result.clearAttempted, noClear);
                        log.error("{} {}(phase={}): {}", tableTag, outcomeLabel(result.status),
                                result.phase, result.error, e);
                        uiLog(taskId, "error", tableTag + " " + outcomeLabel(result.status)
                                + "(phase=" + result.phase + "): " + result.error);
                    } finally {
                        conns.closeAll();
                        runningTables.remove(tableTag);
                        watchdog.unregister(tableTag);
                        refreshRunningDetail(task, runningTables, lastSaveTime);
                        TL_RUNNING.remove();
                        TL_TABLE_TAG.remove();
                        TL_WATCHDOG.remove();
                    }

                    int done = processed.incrementAndGet();
                    boolean ok = OUT_SUCCESS.equals(result.status)
                            || (result.status == null && result.error == null);
                    int okN = ok ? liveSuccess.incrementAndGet() : liveSuccess.get();
                    int failN = ok ? liveFailed.get() : liveFailed.incrementAndGet();
                    publishLiveTableProgress(task, tableTag, 100, tableTotal, lastSaveTime);
                    synchronized (task) {
                        task.setProcessedTables(done);
                        // 圆环：各表进度均值（完成表=100）；勿仅用完成张数导致长时间 0%
                        task.setProgress(calcOverallLiveProgress(taskId, tableTotal, done));
                        task.setSuccessTables(okN);
                        task.setFailedTablesCount(failN);
                        if (runningTables.isEmpty()) {
                            task.setCurrentTable(tableTag + (result.error == null ? " · 完成" : " · 失败"));
                        }
                        saveTaskProgressQuietly(task);
                    }
                    lastSaveTime.set((int) (System.currentTimeMillis() / 1000));
                    log.info("任务进度: 已完成 {}/{} （刚完成 {}, 仍并行 {}）",
                            done, tableTotal, tableTag, runningTables.size());
                    uiLog(taskId, "info", "任务进度: 已完成 " + done + "/" + tableTotal
                            + " （刚完成 " + tableTag + ", 仍并行 " + runningTables.size() + "）");
                    return result;
                }));
            }

            List<TableSyncResult> retryables = new ArrayList<>();
            List<TableSyncResult> successes = new ArrayList<>();
            int successCount = 0;
            int notCleared = 0, clearFailed = 0, syncFailed = 0;
            // 带超时轮询收集 Future，避免单表 JDBC 卡死导致主线程永久阻塞、界面无终态
            boolean[] futureDone = new boolean[futures.size()];
            TableSyncResult[] collected = new TableSyncResult[futures.size()];
            int collectedCount = 0;
            while (collectedCount < futures.size()) {
                long now = System.currentTimeMillis();
                List<String> idleTables = watchdog.abortIdleTables(now, tag -> {
                    long lim = watchdog.tableIdleLimitMs(tag) / 1000;
                    log.error("{} 超过 {}s 无心跳，强制跳过并计为同步失败{}",
                            tag, lim, watchdog.isLargeTable(tag) ? "（超大表时限）" : "");
                    uiLog(taskId, "error", tag + " 超过 " + lim
                            + "s 无心跳，已跳过（计同步失败）");
                    updateTaskDetail(task, lastSaveTime, tag + " · 超时跳过");
                });
                if (!idleTables.isEmpty()) {
                    for (int fi = 0; fi < futures.size(); fi++) {
                        if (futureDone[fi] || futures.get(fi).isDone()) {
                            continue;
                        }
                        String tag = fi < futureTableTags.size() ? futureTableTags.get(fi) : null;
                        if (tag != null && idleTables.contains(tag)) {
                            futures.get(fi).cancel(true);
                        }
                    }
                }
                watchdog.abortIdleTask(now, () -> {
                    cancelledTaskIds.add(taskId);
                    long lim = watchdog.taskIdleLimitMs() / 1000;
                    log.error("整任务超过 {}s 无心跳，判定同步失败并中止剩余表", lim);
                    uiLog(taskId, "error", "整任务超过 " + lim + "s 无心跳，判定同步失败");
                    updateTaskDetail(task, lastSaveTime, "整任务超时失败");
                    for (Future<TableSyncResult> f : futures) {
                        if (!f.isDone()) {
                            f.cancel(true);
                        }
                    }
                });
                // 超时强制断连后若线程仍不退出，最多再等 30s 后合成失败结果，避免主线程永久卡死
                long taskToAt = watchdog.taskTimedOutAtMs();
                if (taskToAt > 0 && now - taskToAt >= 30_000L) {
                    for (int fi = 0; fi < futures.size(); fi++) {
                        if (futureDone[fi]) {
                            continue;
                        }
                        futures.get(fi).cancel(true);
                        TableSyncResult r = new TableSyncResult();
                        r.table = fi < futureTableKeys.size() ? futureTableKeys.get(fi) : "unknown";
                        r.displayName = fi < futureDisplayNames.size() ? futureDisplayNames.get(fi) : r.table;
                        r.tableIndex = fi + 1;
                        r.phase = "error";
                        r.status = OUT_SYNC_FAILED;
                        r.error = "整任务超时后线程未退出，已强制结束";
                        collected[fi] = r;
                        futureDone[fi] = true;
                        collectedCount++;
                    }
                    break;
                }
                for (int fi = 0; fi < futures.size(); fi++) {
                    if (futureDone[fi]) {
                        continue;
                    }
                    String tag = fi < futureTableTags.size() ? futureTableTags.get(fi) : null;
                    long tableToAt = tag != null ? watchdog.tableTimedOutAtMs(tag) : 0L;
                    if (tableToAt > 0 && now - tableToAt >= 30_000L && !futures.get(fi).isDone()) {
                        futures.get(fi).cancel(true);
                        TableSyncResult r = new TableSyncResult();
                        r.table = fi < futureTableKeys.size() ? futureTableKeys.get(fi) : "unknown";
                        r.displayName = fi < futureDisplayNames.size() ? futureDisplayNames.get(fi) : r.table;
                        r.tableIndex = fi + 1;
                        r.phase = "error";
                        r.status = OUT_SYNC_FAILED;
                        r.error = "表同步超时（" + (watchdog.tableIdleLimitMs(tag) / 1000)
                                + "秒无心跳），线程未退出已强制跳过";
                        collected[fi] = r;
                        futureDone[fi] = true;
                        collectedCount++;
                        continue;
                    }
                    Future<TableSyncResult> f = futures.get(fi);
                    try {
                        TableSyncResult r = f.get(FUTURE_POLL_MS, TimeUnit.MILLISECONDS);
                        collected[fi] = r;
                        futureDone[fi] = true;
                        collectedCount++;
                    } catch (TimeoutException te) {
                        // 继续轮询 / 看门狗
                    } catch (CancellationException ce) {
                        TableSyncResult r = new TableSyncResult();
                        r.table = fi < futureTableKeys.size() ? futureTableKeys.get(fi) : "unknown";
                        r.displayName = fi < futureDisplayNames.size() ? futureDisplayNames.get(fi) : r.table;
                        r.tableIndex = fi + 1;
                        r.phase = "error";
                        r.status = OUT_SYNC_FAILED;
                        if (watchdog.isTaskTimedOut()) {
                            r.error = "整任务超时（" + (watchdog.taskIdleLimitMs() / 1000)
                                    + "秒无心跳），表同步已中止";
                        } else {
                            long lim = tag != null ? watchdog.tableIdleLimitMs(tag) / 1000
                                    : SyncIdleWatchdog.DEFAULT_TABLE_IDLE_MS / 1000;
                            r.error = "表同步超时（" + lim + "秒无心跳），已跳过";
                        }
                        collected[fi] = r;
                        futureDone[fi] = true;
                        collectedCount++;
                    } catch (Exception e) {
                        TableSyncResult r = new TableSyncResult();
                        r.table = fi < futureTableKeys.size() ? futureTableKeys.get(fi) : "unknown";
                        r.displayName = fi < futureDisplayNames.size() ? futureDisplayNames.get(fi) : r.table;
                        r.tableIndex = fi + 1;
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        r.error = cause.getMessage() != null ? cause.getMessage() : e.toString();
                        r.phase = "error";
                        r.status = OUT_SYNC_FAILED;
                        collected[fi] = r;
                        futureDone[fi] = true;
                        collectedCount++;
                        log.error("表同步 Future 异常: {}", r.displayName, e);
                    }
                }
            }
            for (int fi = 0; fi < collected.length; fi++) {
                TableSyncResult r = collected[fi];
                if (r == null) {
                    continue;
                }
                if (OUT_SUCCESS.equals(r.status) || (r.status == null && r.error == null)) {
                    successCount++;
                    r.status = OUT_SUCCESS;
                    successes.add(r);
                } else {
                    if (r.status == null) {
                        r.status = OUT_SYNC_FAILED;
                    }
                    retryables.add(r);
                    if (OUT_NOT_CLEARED.equals(r.status)) {
                        notCleared++;
                    } else if (OUT_CLEAR_FAILED.equals(r.status)) {
                        clearFailed++;
                    } else {
                        syncFailed++;
                    }
                }
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("同步线程池未在 30s 内结束，继续关闭连接池");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            uiLog(taskId, "info", "任务连接池即将关闭 " + pools.describeStats());

            boolean wasCancelled = cancelledTaskIds.contains(taskId)
                    || SyncTask.STATUS_CANCELLED.equals(task.getStatus());
            boolean wasTaskIdleTimeout = watchdog.isTaskTimedOut();
            cancelledTaskIds.remove(taskId);

            task.setSuccessTables(successCount);
            task.setFailedTablesCount(retryables.size());
            task.setFailedTables(serializeFailedTables(retryables));
            task.setProgress(100);
            task.setEndTime(LocalDateTime.now());

            if (wasTaskIdleTimeout) {
                task.setStatus(SyncTask.STATUS_FAILED);
                task.setErrorMessage("整任务超时（" + (watchdog.taskIdleLimitMs() / 1000)
                        + "秒无心跳）。成功 " + successCount
                        + "，待重同步 " + retryables.size()
                        + "。" + summarizeFailed(retryables));
            } else if (wasCancelled) {
                task.setStatus(SyncTask.STATUS_CANCELLED);
                task.setErrorMessage("任务已取消。成功 " + successCount
                        + "，待重同步 " + retryables.size()
                        + "（未清理 " + notCleared + " / 清理失败 " + clearFailed + " / 同步失败 " + syncFailed + "）。"
                        + summarizeFailed(retryables));
            } else if (retryables.isEmpty()) {
                task.setStatus(SyncTask.STATUS_SUCCESS);
                task.setErrorMessage(null);
            } else if (successCount == 0) {
                task.setStatus(SyncTask.STATUS_FAILED);
                task.setErrorMessage(summarizeFailed(retryables));
            } else {
                task.setStatus(SyncTask.STATUS_PARTIAL);
                task.setErrorMessage("成功 " + successCount + " 张，待重同步 " + retryables.size()
                        + "（未清理 " + notCleared + " / 清理失败 " + clearFailed + " / 同步失败 " + syncFailed + "）。"
                        + summarizeFailed(retryables));
            }
            task.setCurrentTable("★★★ 同步任务结束 ★★★ " + task.getStatus()
                    + "（成功 " + successCount + " / 待重同步 " + retryables.size() + "）");
            writeSyncReport(task, successes, retryables);
            syncTaskRepository.save(task);

            long elapsedSec = Math.max(1, (System.currentTimeMillis() - taskStartMs) / 1000);
            log.info("============================================================");
            log.info("【同步任务结束】 ★★★ taskId={} status={} ★★★", taskId, task.getStatus());
            log.info("  成功表数 = {}", successCount);
            log.info("  待重同步 = {} (未清理={}, 清理失败={}, 同步失败={})",
                    retryables.size(), notCleared, clearFailed, syncFailed);
            log.info("  总表数   = {}", tables.size());
            log.info("  总耗时   = {}s ({}m {}s)", elapsedSec, elapsedSec / 60, elapsedSec % 60);
            log.info("  报告     = {}", task.getReportPath() != null ? task.getReportPath() : "(未生成)");
            if (!retryables.isEmpty()) {
                log.info("  摘要 = {}", summarizeFailed(retryables));
            }
            log.info("============================================================");
            uiLog(taskId, task.getStatus().equals(SyncTask.STATUS_SUCCESS) ? "success" : "warn",
                    "【同步任务结束】 status=" + task.getStatus()
                            + " 成功=" + successCount + " 待重同步=" + retryables.size()
                            + " 总表=" + tables.size() + " 耗时=" + elapsedSec + "s");

            liveTableProgressPct.remove(taskId);
            TL_WATCHDOG.remove();

        } catch (Exception e) {
            liveTableProgressPct.remove(taskId);
            log.error("============================================================");
            log.error("【同步任务异常结束】 ★★★ taskId={} ★★★", taskId, e);
            log.error("============================================================");
            uiLog(taskId, "error", "【同步任务异常结束】" + toFriendlySyncError(e));
            cancelledTaskIds.remove(taskId);
            TL_WATCHDOG.remove();
            task.setStatus(SyncTask.STATUS_FAILED);
            task.setErrorMessage(toFriendlySyncError(e));
            task.setCurrentTable("★★★ 同步任务结束 ★★★ FAILED（连接或启动失败）");
            task.setEndTime(LocalDateTime.now());
            writeSyncReport(task, Collections.emptyList(), Collections.emptyList());
            syncTaskRepository.save(task);
        }
    }

    private void writeSyncReport(SyncTask task, List<TableSyncResult> successes, List<TableSyncResult> failed) {
        try {
            String path = reportService.generateSyncReport(task, toReportRows(successes), toReportRows(failed));
            task.setReportPath(path);
        } catch (Exception e) {
            log.warn("生成同步报告失败 taskId={}: {}", task.getId(), e.getMessage());
        }
    }

    private List<Map<String, Object>> toReportRows(List<TableSyncResult> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (TableSyncResult r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table);
            m.put("displayName", r.displayName != null ? r.displayName : r.table);
            m.put("tableIndex", r.tableIndex);
            m.put("srcCatalog", r.srcCatalog);
            m.put("tgtCatalog", r.tgtCatalog);
            m.put("status", r.status);
            m.put("statusLabel", outcomeLabel(r.status));
            m.put("error", r.error);
            m.put("phase", r.phase);
            m.put("rowCount", r.rowCount);
            m.put("clearAttempted", r.clearAttempted);
            list.add(m);
        }
        return list;
    }

    // ============ 核心方法 ============

    private boolean supportsMysqlFamily(DatabaseType dbType) {
        return dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB;
    }

    private void checkCancel(Long taskId, String phase, boolean clearAttempted) throws SyncAbortException {
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        String tableTag = TL_TABLE_TAG.get();
        if (wd != null && wd.isTaskTimedOut()) {
            throwTimeoutAbort(phase, clearAttempted,
                    "整任务超时（" + (wd.taskIdleLimitMs() / 1000) + "秒无心跳），已中止");
        }
        if (wd != null && wd.isTableTimedOut(tableTag)) {
            throwTimeoutAbort(phase, clearAttempted,
                    "表同步超时（" + (wd.tableIdleLimitMs(tableTag) / 1000) + "秒无心跳），已跳过");
        }
        if (taskId == null || !cancelledTaskIds.contains(taskId)) {
            return;
        }
        if ("clear".equals(phase) || "ddl".equals(phase)) {
            throw new SyncAbortException(OUT_CLEAR_FAILED, phase, true,
                    "清理未完成（任务已取消），计为清理失败");
        }
        if ("insert".equals(phase) || "datax".equals(phase)) {
            throw new SyncAbortException(OUT_SYNC_FAILED, phase, clearAttempted,
                    clearAttempted ? "清表后写入未完成（任务已取消），计为同步失败"
                            : "同步未完成（任务已取消），计为同步失败");
        }
        throw new SyncAbortException(OUT_NOT_CLEARED, phase, false, "未同步（未清理，任务已取消）");
    }

    private void throwTimeoutAbort(String phase, boolean clearAttempted, String message)
            throws SyncAbortException {
        if ("clear".equals(phase) || "ddl".equals(phase)) {
            throw new SyncAbortException(OUT_CLEAR_FAILED, phase, true, message);
        }
        if ("insert".equals(phase) || "datax".equals(phase) || "upsert".equals(phase)
                || "sync".equals(phase) || clearAttempted) {
            throw new SyncAbortException(OUT_SYNC_FAILED, phase, clearAttempted, message);
        }
        throw new SyncAbortException(OUT_NOT_CLEARED, phase, false, message);
    }

    private String resolveFailureStatus(String phase, boolean clearAttempted, boolean noClear) {
        if (noClear) {
            return OUT_SYNC_FAILED;
        }
        // 清表未完成 / DDL 清空与切换失败 → 清理失败
        if ("clear".equals(phase) || "ddl".equals(phase)) {
            return OUT_CLEAR_FAILED;
        }
        // 已进入写入或清表后失败 → 同步失败
        if ("insert".equals(phase) || "done".equals(phase) || clearAttempted) {
            return OUT_SYNC_FAILED;
        }
        // 尚未清表（连库、读列等）→ 未同步（未清理）
        if ("meta".equals(phase) || "connect".equals(phase) || "prepare".equals(phase) || phase == null) {
            return OUT_NOT_CLEARED;
        }
        return OUT_SYNC_FAILED;
    }

    public static String outcomeLabel(String status) {
        if (OUT_NOT_CLEARED.equals(status)) {
            return "未同步（未清理）";
        }
        if (OUT_CLEAR_FAILED.equals(status)) {
            return "清理失败";
        }
        if (OUT_SYNC_FAILED.equals(status)) {
            return "同步失败";
        }
        if (OUT_SUCCESS.equals(status)) {
            return "已同步";
        }
        return status != null ? status : "未知";
    }

    private static final class SyncAbortException extends Exception {
        final String outcomeStatus;
        final String phase;
        final boolean clearAttempted;

        SyncAbortException(String outcomeStatus, String phase, boolean clearAttempted, String message) {
            super(message);
            this.outcomeStatus = outcomeStatus;
            this.phase = phase;
            this.clearAttempted = clearAttempted;
        }
    }

    private String describeStrategy(String syncMode) {
        String m = SyncTask.normalizeMode(syncMode);
        if (SyncTask.MODE_DATA_TRANSFER.equals(m)) {
            return "数据传输（类 Navicat）：清空目标表后全量 INSERT";
        }
        if (SyncTask.MODE_DATA_SYNC.equals(m)) {
            return "数据同步（类 Navicat）：按主键 INSERT 新行 / UPDATE 差异 / DELETE 多余行";
        }
        if (SyncTask.MODE_INCREMENTAL.equals(m)) {
            return "增量插入 INSERT IGNORE（不清空、不更新已有行）";
        }
        if (SyncTask.MODE_PK_UPSERT.equals(m)) {
            return "增量合并（按主键 UPDATE 已有 + INSERT 新行，不清空；适合差异不大）";
        }
        if (SyncTask.MODE_FULL_DDL.equals(m)) {
            return "DDL 全量（CREATE LIKE/RENAME 或 TRUNCATE + INSERT）";
        }
        if (SyncTask.MODE_DATAX.equals(m)) {
            return "DataX 工具同步（本机 datax.py；清表默认 AUTO=TRUNCATE 失败则 DELETE）";
        }
        if (SyncTask.MODE_HYBRID.equals(m)) {
            return "混合：单表估算≥" + SyncHybridRouter.DATAX_ROW_THRESHOLD
                    + " 行且已配置 DataX → DataX，否则普通 DML 全量（DELETE+INSERT）";
        }
        return "DML 全量（分批 DELETE + 批量 INSERT）";
    }

    /**
     * 纯 DataX 模式，或混合模式下达到百万行且已配置 DataX。
     */
    private boolean shouldRunDatax(SyncTask task, long estimatedRows, String tableTag, Long taskId) {
        if (task.isDataxMode()) {
            return true;
        }
        if (!task.isHybridMode()) {
            return false;
        }
        AppSettings settings = appSettingsService.getSettings();
        String home = settings != null ? settings.getDataxHome() : null;
        boolean configured = SyncHybridRouter.isDataxConfigured(home);
        boolean useDatax = SyncHybridRouter.shouldUseDatax(estimatedRows, home);
        String decision = SyncHybridRouter.describeDecision(estimatedRows, useDatax, configured);
        log.info("{} 混合模式路由: {}", tableTag, decision);
        uiLog(taskId, useDatax ? "info" : (estimatedRows >= SyncHybridRouter.DATAX_ROW_THRESHOLD && !configured
                ? "warn" : "info"), tableTag + " 混合模式: " + decision);
        return useDatax;
    }

    /**
     * DDL 全量（MySQL/GoldenDB）：优先 CREATE LIKE → INSERT → RENAME；
     * CREATE 失败则 TRUNCATE → INSERT；仍失败则回退 DML DELETE → INSERT。
     * 长清表后会重建连接，避免 wait_timeout。
     */
    private long fullSyncByDdl(WorkConns conns, DatabaseType sourceDbType, DatabaseType targetDbType,
                               String srcQualified, String tgtCatalog, String tableName, String tableTag,
                               List<String> columns, List<String> primaryKeys, int batchSize,
                               SyncTask task, AtomicInteger lastSaveTime, long estimatedRows)
            throws SQLException, SyncAbortException {
        String live = buildQualifiedName(tgtCatalog, tableName, targetDbType);
        String token = String.valueOf(System.currentTimeMillis());
        String stagingName = shortIdent(tableName, STAGING_NEW_SUFFIX + "_" + token);
        String oldName = shortIdent(tableName, STAGING_OLD_SUFFIX + "_" + token);
        String staging = buildQualifiedName(tgtCatalog, stagingName, targetDbType);
        String oldQualified = buildQualifiedName(tgtCatalog, oldName, targetDbType);

        updateTaskDetail(task, lastSaveTime, tableTag + " · CREATE LIKE");
        try (Statement stmt = conns.tgt.createStatement()) {
            stmt.execute("CREATE TABLE " + staging + " LIKE " + live);
            log.info("{} 已 CREATE LIKE 临时表 {}", tableTag, stagingName);
        } catch (SQLException e) {
            log.warn("{} CREATE LIKE 失败: {} — 尝试 TRUNCATE", tableTag, e.getMessage());
            dropTableBestEffort(conns.tgt, staging);
            boolean truncated = truncateTable(conns.tgt, tgtCatalog, tableName, tableTag, targetDbType, task, lastSaveTime);
            if (!truncated) {
                log.warn("{} TRUNCATE 也失败，回退 DML 分批 DELETE", tableTag);
                clearTable(conns, tgtCatalog, tableName, tableTag, targetDbType, batchSize, task, lastSaveTime);
            }
            conns.recycleAfterLongPhase(tableTag);
            updateTaskDetail(task, lastSaveTime, tableTag + (truncated ? " · TRUNCATE后写入" : " · DML回退写入"));
            return batchCopyData(conns, srcQualified, live,
                    columns, primaryKeys, sourceDbType, targetDbType, batchSize, tableTag, task, lastSaveTime,
                    false, estimatedRows);
        }

        try {
            conns.borrowSource();
            updateTaskDetail(task, lastSaveTime, tableTag + " · 写入临时表");
            long rows = batchCopyData(conns, srcQualified, staging,
                    columns, primaryKeys, sourceDbType, targetDbType, batchSize, tableTag, task, lastSaveTime,
                    false, estimatedRows);

            updateTaskDetail(task, lastSaveTime, tableTag + " · RENAME 切换");
            String renameSql = "RENAME TABLE " + live + " TO " + oldQualified
                    + ", " + staging + " TO " + live;
            long renameStart = System.currentTimeMillis();
            try (Statement stmt = conns.tgt.createStatement()) {
                stmt.execute(renameSql);
            }
            log.info("{} ★ RENAME 切换完成 ★ 耗时 {}ms（旧表 → {}）",
                    tableTag, System.currentTimeMillis() - renameStart, oldName);

            if (!dropTableBestEffort(conns.tgt, oldQualified)) {
                log.warn("{} 旧备份 {} 无法 DROP，尝试 TRUNCATE 旧备份", tableTag, oldName);
                try (Statement stmt = conns.tgt.createStatement()) {
                    stmt.execute("TRUNCATE TABLE " + oldQualified);
                } catch (SQLException te) {
                    log.warn("{} 旧备份清理失败，请手工 DROP {}: {}", tableTag, oldName, te.getMessage());
                }
            }
            return rows;
        } catch (SQLException e) {
            dropTableBestEffort(conns.tgt, staging);
            throw e;
        }
    }

    /**
     * 工作线程借还连接的句柄。Connection 仅本线程使用；close/归还后由池复用物理连接。
     */
    private final class WorkConns {
        private final SyncJdbcPools pools;
        private final String sourceSchema;
        private final boolean multiSource;
        private final String targetSchema;
        private final boolean multiTarget;
        private final DatabaseType targetDbType;
        private final Long taskId;
        Connection src;
        Connection tgt;
        /** 数据同步归并读目标用（与写入连接分离，避免流式查询与 DML 同连接冲突） */
        Connection tgtRead;

        WorkConns(SyncJdbcPools pools, String sourceSchema, boolean multiSource,
                  String targetSchema, boolean multiTarget, DatabaseType targetDbType, Long taskId) {
            this.pools = pools;
            this.sourceSchema = sourceSchema;
            this.multiSource = multiSource;
            this.targetSchema = targetSchema;
            this.multiTarget = multiTarget;
            this.targetDbType = targetDbType;
            this.taskId = taskId;
        }

        void borrowSource() throws SQLException {
            releaseSource();
            src = borrowWithHeartbeat("源", () -> pools.borrowSource(sourceSchema, multiSource));
        }

        void borrowTarget(boolean hints) throws SQLException {
            releaseTarget();
            tgt = borrowWithHeartbeat("目标", () -> pools.borrowTarget(targetSchema, multiTarget, hints));
        }

        /** 并行清表 worker：额外借一条目标连接（调用方负责 release） */
        Connection borrowExtraTarget(boolean hints) throws SQLException {
            return borrowWithHeartbeat("目标清表", () -> pools.borrowTarget(targetSchema, multiTarget, hints));
        }

        void borrowTargetRead() throws SQLException {
            releaseTargetRead();
            tgtRead = borrowWithHeartbeat("目标读", () -> pools.borrowTarget(targetSchema, multiTarget, false));
        }

        /**
         * Hikari 单次借出超时较短；等池期间持续 touch 看门狗并打【心跳】，避免内网高并行误杀。
         */
        private Connection borrowWithHeartbeat(String role, ConnBorrow call) throws SQLException {
            SQLException last = null;
            long waitStart = System.currentTimeMillis();
            long lastLogAt = 0L;
            for (int attempt = 1; attempt <= 60; attempt++) {
                String tag = TL_TABLE_TAG.get();
                SyncIdleWatchdog wd = TL_WATCHDOG.get();
                if (wd != null && tag != null && wd.isTableTimedOut(tag)) {
                    throw new SQLException("表已超时跳过，停止等待" + role + "连接");
                }
                if (taskId != null && cancelledTaskIds.contains(taskId)) {
                    throw new SQLException("任务已取消，停止等待" + role + "连接");
                }
                touchWatchdog();
                try {
                    return call.get();
                } catch (SQLException e) {
                    last = e;
                    if (!isPoolAcquireTimeout(e)) {
                        throw e;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastLogAt >= 5_000L) {
                        String heartbeat = "【心跳】仍在运行 · 等待" + role + "连接池借出 (已等 "
                                + ((now - waitStart) / 1000) + "s, 第 " + attempt + " 次)";
                        if (tag != null) {
                            log.info("{} {}", tag, heartbeat);
                            if (taskId != null) {
                                uiLog(taskId, "info", tag + " " + heartbeat);
                            }
                        } else {
                            log.info("{}", heartbeat);
                        }
                        lastLogAt = now;
                    } else {
                        touchWatchdog();
                    }
                }
            }
            throw last != null ? last : new SQLException("等待" + role + "连接超时");
        }

        void releaseSource() {
            SyncJdbcPools.release(src);
            src = null;
        }

        void releaseTarget() {
            if (tgt != null) {
                restoreSessionSpeedHints(tgt, targetDbType);
            }
            SyncJdbcPools.release(tgt);
            tgt = null;
        }

        void releaseTargetRead() {
            SyncJdbcPools.release(tgtRead);
            tgtRead = null;
        }

        /** 协议损坏：从池中剔除物理连接（勿用普通 close 把坏连接还回去）。 */
        void discardTarget() {
            if (tgt != null) {
                pools.evictTarget(tgt);
                tgt = null;
            }
        }

        void discardSource() {
            if (src != null) {
                pools.evictSource(src);
                src = null;
            }
        }

        void discardTargetRead() {
            if (tgtRead != null) {
                pools.evictTarget(tgtRead);
                tgtRead = null;
            }
        }

        void discardAll() {
            discardSource();
            discardTarget();
            discardTargetRead();
        }

        /**
         * 长阶段结束后归还并重新借出：走池校验，复用池内空闲物理连接（非 abort 新建）。
         */
        void recycleAfterLongPhase(String tableTag) throws SQLException {
            log.info("{} 阶段结束，归还并重新借出池连接（复用，非新建握手） {}", tableTag, pools.describeStats());
            closeAll();
            borrowSource();
            borrowTarget(true);
        }

        /** 清表中期仅重借目标连接（归还回池后再借）。 */
        void recycleTargetOnly(String tableTag) throws SQLException {
            log.debug("{} 归还并重借目标连接（池复用）", tableTag);
            releaseTarget();
            borrowTarget(true);
        }

        /**
         * 写入重试：优先归还回池再借；仅连接已死/协议损坏时剔除。
         */
        void reborrowTargetAfterError(Throwable error, boolean forceEvict) throws SQLException {
            if (forceEvict || shouldEvictAfterError(error, tgt)) {
                discardTarget();
            } else {
                releaseTarget();
            }
            borrowTarget(true);
        }

        void reborrowSourceAfterError(Throwable error, boolean forceEvict) throws SQLException {
            if (forceEvict || shouldEvictAfterError(error, src)) {
                discardSource();
            } else {
                releaseSource();
            }
            borrowSource();
        }

        void closeAll() {
            releaseTargetRead();
            releaseTarget();
            releaseSource();
        }
    }

    @FunctionalInterface
    private interface ConnBorrow {
        Connection get() throws SQLException;
    }

    /** Hikari 借出超时（池满/建连慢），可带心跳重试。 */
    private static boolean isPoolAcquireTimeout(SQLException e) {
        if (e == null) {
            return false;
        }
        if (e instanceof java.sql.SQLTransientConnectionException) {
            return true;
        }
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        String lower = m.toLowerCase();
        return lower.contains("connection is not available")
                || lower.contains("connectiontimeout")
                || lower.contains("timed out waiting for connection");
    }

    private static boolean shouldEvictAfterError(Throwable error, Connection conn) {
        if (conn == null) {
            return true;
        }
        try {
            if (conn.isClosed()) {
                return true;
            }
        } catch (SQLException e) {
            return true;
        }
        return isConnectivityFailure(error);
    }

    private boolean truncateTable(Connection conn, String catalog, String tableName, String tableTag,
                                  DatabaseType dbType, SyncTask task, AtomicInteger lastSaveTime) {
        String qualified = buildQualifiedName(catalog, tableName, dbType);
        updateTaskDetail(task, lastSaveTime, tableTag + " · TRUNCATE");
        long start = System.currentTimeMillis();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + qualified);
            log.info("{} TRUNCATE 完成，耗时 {}ms", tableTag, System.currentTimeMillis() - start);
            return true;
        } catch (SQLException e) {
            log.warn("{} TRUNCATE 失败: {}", tableTag, e.getMessage());
            return false;
        }
    }

    private boolean dropTableBestEffort(Connection conn, String qualifiedName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + qualifiedName);
            return true;
        } catch (SQLException e) {
            log.debug("DROP TABLE {} 失败: {}", qualifiedName, e.getMessage());
            return false;
        }
    }

    private String shortIdent(String tableName, String suffix) {
        String full = tableName + suffix;
        if (full.length() <= 64) {
            return full;
        }
        int keep = Math.max(8, 64 - suffix.length());
        return tableName.substring(0, Math.min(keep, tableName.length())) + suffix;
    }

    private void restoreSessionSpeedHints(Connection conn, DatabaseType dbType) {
        if (!supportsMysqlFamily(dbType) || conn == null) {
            return;
        }
        try {
            if (conn.isClosed()) {
                return;
            }
        } catch (SQLException e) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET UNIQUE_CHECKS=1");
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");
        } catch (SQLException e) {
            String m = e.getMessage();
            // 连接已失效/归还后常见；message 为 null 时勿刷屏 WARN
            if (m == null || m.isEmpty()
                    || m.toLowerCase().contains("closed")
                    || m.toLowerCase().contains("connection is closed")) {
                log.debug("恢复会话参数跳过: {}", e.getClass().getSimpleName());
            } else {
                log.warn("恢复会话参数失败: {}", m);
            }
        }
    }

    /**
     * 类 Navicat 数据同步：源/目标按主键有序归并。
     * INSERT 源有目标无；UPDATE 主键相同但非主键列不同；DELETE 目标有源无。
     * 读目标用独立连接，写操作走写入连接。
     */
    /**
     * 类 Navicat 差异同步：优先主键分块（短查询 + 页级心跳），避免双端全表 ORDER BY 流式游标
     * 在首包返回前长时间无心跳被看门狗误杀。
     */
    private long navicatDataSync(WorkConns conns,
                                 String srcQualified, String tgtQualified,
                                 List<String> columns, List<String> primaryKeys,
                                 DatabaseType sourceDbType, DatabaseType targetDbType,
                                 int batchSize, String tableName, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        if (canUseKeysetPaging(columns, primaryKeys, sourceDbType)
                && canUseKeysetPaging(columns, primaryKeys, targetDbType)) {
            return navicatDataSyncByKeyset(conns, srcQualified, tgtQualified,
                    columns, primaryKeys, sourceDbType, targetDbType, batchSize,
                    tableName, task, lastSaveTime);
        }
        log.warn("{} 差异同步无法使用主键分块，回退双端流式归并（大表易卡在打开游标）", tableName);
        return navicatDataSyncByStreamMerge(conns, srcQualified, tgtQualified,
                columns, primaryKeys, sourceDbType, targetDbType, batchSize,
                tableName, task, lastSaveTime);
    }

    /**
     * 差异同步主键分块：
     * <ol>
     *   <li>按源表 keyset 翻页，用 PK IN 拉目标对应行 → INSERT/UPDATE</li>
     *   <li>按目标表 keyset 翻页，用 PK IN 查源是否存在 → DELETE 孤儿行</li>
     * </ol>
     * 每页均 touch 看门狗，不再依赖双端全表排序流。
     */
    private long navicatDataSyncByKeyset(WorkConns conns,
                                        String srcQualified, String tgtQualified,
                                        List<String> columns, List<String> primaryKeys,
                                        DatabaseType sourceDbType, DatabaseType targetDbType,
                                        int batchSize, String tableName, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        int[] pkIdx = SyncDataSyncHelper.primaryKeyIndexes(columns, primaryKeys);
        List<String> pkQuotedSrc = new ArrayList<>(primaryKeys.size());
        List<String> pkQuotedTgt = new ArrayList<>(primaryKeys.size());
        for (String pk : primaryKeys) {
            pkQuotedSrc.add(DatabaseDialect.escapeIdentifier(pk, sourceDbType));
            pkQuotedTgt.add(DatabaseDialect.escapeIdentifier(pk, targetDbType));
        }
        String selectColsSrc = quoteColumnList(columns, sourceDbType);
        String selectColsTgt = quoteColumnList(columns, targetDbType);

        String insertSql = buildGenericInsertSql(tgtQualified, columns, targetDbType);
        String updateSql = buildGenericUpdateSql(tgtQualified, columns, primaryKeys, targetDbType);
        String deleteSql = buildPkDeleteSql(tgtQualified, primaryKeys, targetDbType);

        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " [归并同步·主键分块] 每页≤" + KEYSET_PAGE_ROWS
                    + " · INSERT/UPDATE 后扫目标删孤儿");
        }
        log.info("{} 差异同步主键分块: page≤{}, inChunk≤{}, pk={}",
                tableName, KEYSET_PAGE_ROWS, DATA_SYNC_PK_IN_CHUNK, primaryKeys);

        Connection srcConn = conns.src;
        Connection tgtRead = conns.tgtRead;
        Connection tgtWrite = conns.tgt;
        srcConn.setAutoCommit(true);
        tgtRead.setAutoCommit(true);
        tgtWrite.setAutoCommit(false);

        long inserted = 0, updated = 0, deleted = 0, scanned = 0;
        long start = System.currentTimeMillis();
        long lastLogAt = start;
        int pages = 0;

        List<Object[]> insertBuf = new ArrayList<>(Math.min(batchSize, 2048));
        List<Object[]> updateBuf = new ArrayList<>(Math.min(batchSize, 2048));
        List<Object[]> deleteBuf = new ArrayList<>(Math.min(batchSize, 2048));

        PreparedStatement insertStmt = null;
        PreparedStatement updateStmt = null;
        PreparedStatement deleteStmt = null;

        try {
            insertStmt = tgtWrite.prepareStatement(insertSql);
            updateStmt = tgtWrite.prepareStatement(updateSql);
            deleteStmt = tgtWrite.prepareStatement(deleteSql);

            // —— Phase A: 源 → 目标 INSERT/UPDATE ——
            Object[] lastSrcPk = null;
            while (true) {
                touchWatchdog();
                checkCancel(task.getId(), "sync", false);
                List<Object[]> srcPage = fetchKeysetPage(srcConn, srcQualified, selectColsSrc, pkQuotedSrc,
                        lastSrcPk, KEYSET_PAGE_ROWS, columns.size());
                if (srcPage.isEmpty()) {
                    break;
                }
                pages++;
                scanned += srcPage.size();

                Map<String, Object[]> tgtByPk = fetchRowsByPrimaryKeys(tgtRead, tgtQualified, selectColsTgt,
                        pkQuotedTgt, srcPage, pkIdx, columns.size());
                for (Object[] srcRow : srcPage) {
                    String key = SyncDataSyncHelper.pkMapKey(srcRow, pkIdx);
                    Object[] tgtRow = tgtByPk.get(key);
                    if (tgtRow == null) {
                        insertBuf.add(srcRow);
                        inserted++;
                    } else if (!SyncDataSyncHelper.nonPrimaryEquals(srcRow, tgtRow, pkIdx)) {
                        updateBuf.add(srcRow);
                        updated++;
                    }
                    if (insertBuf.size() >= batchSize || updateBuf.size() >= batchSize) {
                        flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                                columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);
                    }
                }
                lastSrcPk = extractPkValues(srcPage.get(srcPage.size() - 1), pkIdx);
                lastLogAt = maybeLogDataSyncProgress(tableName, scanned, inserted, updated, deleted,
                        start, lastLogAt, task, lastSaveTime);
            }
            flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                    columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);

            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "info", tableName + " 【心跳】仍在运行 · 源端比对完成，开始扫描目标删孤儿行");
            }
            touchWatchdog();

            // —— Phase B: 目标孤儿 DELETE ——
            Object[] lastTgtPk = null;
            while (true) {
                touchWatchdog();
                checkCancel(task.getId(), "sync", false);
                List<Object[]> tgtPage = fetchKeysetPage(tgtRead, tgtQualified, selectColsTgt, pkQuotedTgt,
                        lastTgtPk, KEYSET_PAGE_ROWS, columns.size());
                if (tgtPage.isEmpty()) {
                    break;
                }
                pages++;
                scanned += tgtPage.size();

                Set<String> srcKeys = fetchPkKeysByPrimaryKeys(srcConn, srcQualified, pkQuotedSrc,
                        tgtPage, pkIdx);
                for (Object[] tgtRow : tgtPage) {
                    String key = SyncDataSyncHelper.pkMapKey(tgtRow, pkIdx);
                    if (!srcKeys.contains(key)) {
                        deleteBuf.add(tgtRow);
                        deleted++;
                    }
                    if (deleteBuf.size() >= batchSize) {
                        flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                                columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);
                    }
                }
                lastTgtPk = extractPkValues(tgtPage.get(tgtPage.size() - 1), pkIdx);
                lastLogAt = maybeLogDataSyncProgress(tableName, scanned, inserted, updated, deleted,
                        start, lastLogAt, task, lastSaveTime);
            }
            flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                    columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);
        } catch (SQLException e) {
            safeRollback(conns.tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
                conns.discardTargetRead();
                if (shouldEvictAfterError(e, conns.src)) {
                    conns.discardSource();
                }
            }
            throw enrichSyncSqlException(e,
                    "phase=sync（主键分块差异同步）。请对该表重跑，或降低表级并行/批次。");
        } finally {
            closeQuietly(insertStmt);
            closeQuietly(updateStmt);
            closeQuietly(deleteStmt);
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - start) / 1000);
        long touched = inserted + updated + deleted;
        log.info("{} 数据同步结束(keyset): 扫描≈{}, 页={}, +insert={}, ~update={}, -delete={}, 耗时 {}s",
                tableName, scanned, pages, inserted, updated, deleted, elapsedSec);
        warnIfSuspiciousSelectCap(tableName, scanned, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 数据同步结束: +" + inserted
                    + " 更新 " + updated + " 删除 " + deleted + ", " + pages + " 页, 耗时 " + elapsedSec + "s");
        }
        return Math.max(touched, scanned);
    }

    /** 按主键 IN 拉取完整行，返回 pkMapKey → row。 */
    private Map<String, Object[]> fetchRowsByPrimaryKeys(Connection conn, String qualified, String selectColumns,
                                                         List<String> pkQuoted, List<Object[]> keyRows,
                                                         int[] pkIdx, int columnCount) throws SQLException {
        Map<String, Object[]> out = new HashMap<>(Math.max(16, keyRows.size() * 2));
        if (keyRows.isEmpty()) {
            return out;
        }
        for (int from = 0; from < keyRows.size(); from += DATA_SYNC_PK_IN_CHUNK) {
            touchWatchdog();
            int to = Math.min(from + DATA_SYNC_PK_IN_CHUNK, keyRows.size());
            List<Object[]> chunk = keyRows.subList(from, to);
            String sql = buildPkInSelectSql(selectColumns, qualified, pkQuoted, chunk.size());
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindPkInParams(ps, chunk, pkIdx);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] row = new Object[columnCount];
                        for (int i = 0; i < columnCount; i++) {
                            row[i] = rs.getObject(i + 1);
                        }
                        out.put(SyncDataSyncHelper.pkMapKey(row, pkIdx), row);
                    }
                }
            }
        }
        return out;
    }

    /** 按主键 IN 仅判断源侧是否存在，返回存在的 pkMapKey 集合。 */
    private Set<String> fetchPkKeysByPrimaryKeys(Connection conn, String qualified, List<String> pkQuoted,
                                                 List<Object[]> keyRows, int[] pkIdx) throws SQLException {
        Set<String> out = new HashSet<>(Math.max(16, keyRows.size() * 2));
        if (keyRows.isEmpty()) {
            return out;
        }
        String pkSelect = String.join(", ", pkQuoted);
        for (int from = 0; from < keyRows.size(); from += DATA_SYNC_PK_IN_CHUNK) {
            touchWatchdog();
            int to = Math.min(from + DATA_SYNC_PK_IN_CHUNK, keyRows.size());
            List<Object[]> chunk = keyRows.subList(from, to);
            String sql = buildPkInSelectSql(pkSelect, qualified, pkQuoted, chunk.size());
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindPkInParams(ps, chunk, pkIdx);
                try (ResultSet rs = ps.executeQuery()) {
                    int pkCount = pkQuoted.size();
                    while (rs.next()) {
                        Object[] pkVals = new Object[pkCount];
                        for (int i = 0; i < pkCount; i++) {
                            pkVals[i] = rs.getObject(i + 1);
                        }
                        out.add(SyncDataSyncHelper.pkValuesMapKey(pkVals));
                    }
                }
            }
        }
        return out;
    }

    private static void bindPkInParams(PreparedStatement ps, List<Object[]> keyRows, int[] pkIdx)
            throws SQLException {
        int p = 1;
        for (Object[] row : keyRows) {
            for (int pkIndex : pkIdx) {
                ps.setObject(p++, row[pkIndex]);
            }
        }
    }

    /**
     * {@code SELECT cols FROM t WHERE (pk…) IN ((?),…)} — rowCount 为 IN 元组个数。
     */
    static String buildPkInSelectSql(String selectColumns, String qualified,
                                     List<String> pkQuoted, int rowCount) {
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount must be >= 1");
        }
        String pkList = String.join(", ", pkQuoted);
        StringBuilder oneTuple = new StringBuilder("(");
        for (int i = 0; i < pkQuoted.size(); i++) {
            if (i > 0) {
                oneTuple.append(", ");
            }
            oneTuple.append("?");
        }
        oneTuple.append(")");
        StringBuilder inList = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                inList.append(", ");
            }
            inList.append(oneTuple);
        }
        return "SELECT " + selectColumns + " FROM " + qualified
                + " WHERE (" + pkList + ") IN (" + inList + ")";
    }

    /** 回退：双端全表有序流式归并（仅当无法 keyset 时）。 */
    private long navicatDataSyncByStreamMerge(WorkConns conns,
                                              String srcQualified, String tgtQualified,
                                              List<String> columns, List<String> primaryKeys,
                                              DatabaseType sourceDbType, DatabaseType targetDbType,
                                              int batchSize, String tableName, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        int[] pkIdx = SyncDataSyncHelper.primaryKeyIndexes(columns, primaryKeys);
        String orderBySrc = SyncDataSyncHelper.buildOrderBy(primaryKeys,
                c -> DatabaseDialect.escapeIdentifier(c, sourceDbType));
        String orderByTgt = SyncDataSyncHelper.buildOrderBy(primaryKeys,
                c -> DatabaseDialect.escapeIdentifier(c, targetDbType));
        String selectSrc = "SELECT " + quoteColumnList(columns, sourceDbType) + " FROM " + srcQualified
                + " ORDER BY " + orderBySrc;
        String selectTgt = "SELECT " + quoteColumnList(columns, targetDbType) + " FROM " + tgtQualified
                + " ORDER BY " + orderByTgt;

        String insertSql = buildGenericInsertSql(tgtQualified, columns, targetDbType);
        String updateSql = buildGenericUpdateSql(tgtQualified, columns, primaryKeys, targetDbType);
        String deleteSql = buildPkDeleteSql(tgtQualified, primaryKeys, targetDbType);

        log.info("{} [源 SELECT] {}", tableName, shortenSql(selectSrc, 400));
        log.info("{} [目标 SELECT] {}", tableName, shortenSql(selectTgt, 400));
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " [归并同步·流式] INSERT/UPDATE/DELETE by PK");
        }

        Connection srcConn = conns.src;
        Connection tgtRead = conns.tgtRead;
        Connection tgtWrite = conns.tgt;
        srcConn.setAutoCommit(true);
        tgtRead.setAutoCommit(true);
        tgtWrite.setAutoCommit(false);

        long inserted = 0, updated = 0, deleted = 0, scanned = 0;
        long start = System.currentTimeMillis();
        long lastLogAt = start;
        long lastTouchAt = start;

        List<Object[]> insertBuf = new ArrayList<>(Math.min(batchSize, 2048));
        List<Object[]> updateBuf = new ArrayList<>(Math.min(batchSize, 2048));
        List<Object[]> deleteBuf = new ArrayList<>(Math.min(batchSize, 2048));

        PreparedStatement insertStmt = null;
        PreparedStatement updateStmt = null;
        PreparedStatement deleteStmt = null;

        ScheduledExecutorService pulse = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "data-sync-pulse-" + tableName);
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> pulseFut = pulse.scheduleAtFixedRate(() -> {
            try {
                touchWatchdog();
            } catch (Throwable ignored) {
            }
        }, 5, 5, TimeUnit.SECONDS);

        try (PreparedStatement srcPs = srcConn.prepareStatement(selectSrc);
             PreparedStatement tgtPs = tgtRead.prepareStatement(selectTgt)) {
            try {
                srcPs.setMaxRows(0);
                tgtPs.setMaxRows(0);
            } catch (SQLException ignored) {
            }
            if (sourceDbType == DatabaseType.MYSQL || sourceDbType == DatabaseType.GOLDENDB) {
                srcPs.setFetchSize(Integer.MIN_VALUE);
            } else {
                srcPs.setFetchSize(batchSize);
            }
            if (targetDbType == DatabaseType.MYSQL || targetDbType == DatabaseType.GOLDENDB) {
                tgtPs.setFetchSize(Integer.MIN_VALUE);
            } else {
                tgtPs.setFetchSize(batchSize);
            }

            insertStmt = tgtWrite.prepareStatement(insertSql);
            updateStmt = tgtWrite.prepareStatement(updateSql);
            deleteStmt = tgtWrite.prepareStatement(deleteSql);

            touchWatchdog();
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "info", tableName + " 【心跳】仍在运行 · 正在打开源表有序游标");
            }
            ResultSet srcRs = null;
            ResultSet tgtRs = null;
            try {
                srcRs = srcPs.executeQuery();
                touchWatchdog();
                if (task != null && task.getId() != null) {
                    uiLog(task.getId(), "info", tableName + " 【心跳】仍在运行 · 正在打开目标表有序游标");
                }
                tgtRs = tgtPs.executeQuery();
                touchWatchdog();

                Object[] srcRow = nextRow(srcRs, columns.size());
                Object[] tgtRow = nextRow(tgtRs, columns.size());

                while (srcRow != null || tgtRow != null) {
                    scanned++;

                    if (srcRow != null && tgtRow != null) {
                        int cmp = SyncDataSyncHelper.comparePrimaryKeys(srcRow, tgtRow, pkIdx);
                        if (cmp < 0) {
                            insertBuf.add(srcRow);
                            inserted++;
                            srcRow = nextRow(srcRs, columns.size());
                        } else if (cmp > 0) {
                            deleteBuf.add(tgtRow);
                            deleted++;
                            tgtRow = nextRow(tgtRs, columns.size());
                        } else {
                            if (!SyncDataSyncHelper.nonPrimaryEquals(srcRow, tgtRow, pkIdx)) {
                                updateBuf.add(srcRow);
                                updated++;
                            }
                            srcRow = nextRow(srcRs, columns.size());
                            tgtRow = nextRow(tgtRs, columns.size());
                        }
                    } else if (srcRow != null) {
                        insertBuf.add(srcRow);
                        inserted++;
                        srcRow = nextRow(srcRs, columns.size());
                    } else {
                        deleteBuf.add(tgtRow);
                        deleted++;
                        tgtRow = nextRow(tgtRs, columns.size());
                    }

                    if (insertBuf.size() >= batchSize || updateBuf.size() >= batchSize || deleteBuf.size() >= batchSize) {
                        flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                                columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);
                        lastTouchAt = System.currentTimeMillis();
                    }

                    long now = System.currentTimeMillis();
                    if (scanned % 1000 == 0 || now - lastTouchAt >= 5_000L) {
                        touchWatchdog();
                        checkCancel(task.getId(), "sync", false);
                        lastTouchAt = now;
                    }
                    if (scanned % 5000 == 0) {
                        lastLogAt = maybeLogDataSyncProgress(tableName, scanned, inserted, updated, deleted,
                                start, lastLogAt, task, lastSaveTime);
                    }
                }

                flushDataSyncBuffers(conns, insertStmt, updateStmt, deleteStmt,
                        columns, primaryKeys, insertBuf, updateBuf, deleteBuf, tableName);
            } finally {
                closeQuietly(srcRs);
                closeQuietly(tgtRs);
            }
        } catch (SQLException e) {
            safeRollback(conns.tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
                conns.discardTargetRead();
            }
            throw e;
        } finally {
            pulseFut.cancel(false);
            pulse.shutdownNow();
            closeQuietly(insertStmt);
            closeQuietly(updateStmt);
            closeQuietly(deleteStmt);
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - start) / 1000);
        long touched = inserted + updated + deleted;
        log.info("{} 数据同步结束(流式): 扫描≈{}, +insert={}, ~update={}, -delete={}, 耗时 {}s",
                tableName, scanned, inserted, updated, deleted, elapsedSec);
        warnIfSuspiciousSelectCap(tableName, scanned, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 数据同步结束: +" + inserted
                    + " 更新 " + updated + " 删除 " + deleted + ", 耗时 " + elapsedSec + "s");
        }
        return Math.max(touched, scanned);
    }

    private static Object[] nextRow(ResultSet rs, int colCount) throws SQLException {
        if (!rs.next()) {
            return null;
        }
        Object[] row = new Object[colCount];
        for (int i = 0; i < colCount; i++) {
            row[i] = rs.getObject(i + 1);
        }
        return row;
    }

    private String buildPkDeleteSql(String qualified, List<String> primaryKeys, DatabaseType dbType) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            where.append(DatabaseDialect.escapeIdentifier(primaryKeys.get(i), dbType)).append("=?");
        }
        return "DELETE FROM " + qualified + " WHERE " + where;
    }

    private void flushDataSyncBuffers(WorkConns conns,
                                      PreparedStatement insertStmt, PreparedStatement updateStmt,
                                      PreparedStatement deleteStmt,
                                      List<String> columns, List<String> primaryKeys,
                                      List<Object[]> insertBuf, List<Object[]> updateBuf, List<Object[]> deleteBuf,
                                      String tableTag) throws SQLException {
        touchWatchdog();
        Connection tgt = conns.tgt;
        try {
            if (!deleteBuf.isEmpty()) {
                Map<String, Integer> colIndex = colIndexMap(columns);
                for (Object[] row : deleteBuf) {
                    int p = 1;
                    for (String pk : primaryKeys) {
                        Integer idx = colIndex.get(pk.toLowerCase(Locale.ROOT));
                        deleteStmt.setObject(p++, idx != null ? row[idx] : null);
                    }
                    deleteStmt.addBatch();
                }
                deleteStmt.executeBatch();
                deleteStmt.clearBatch();
                deleteBuf.clear();
            }
            if (!updateBuf.isEmpty()) {
                List<Object[]> pending = new ArrayList<>(updateBuf);
                updateBuf.clear();
                // 复用 generic upsert 的 UPDATE 绑定逻辑：仅 UPDATE（行已存在）
                Map<String, Integer> colIndex = colIndexMap(columns);
                List<String> nonPk = SyncUpsertHelper.nonPrimaryColumns(columns, primaryKeys);
                for (Object[] row : pending) {
                    int p = 1;
                    for (String col : nonPk) {
                        Integer idx = colIndex.get(col.toLowerCase(Locale.ROOT));
                        updateStmt.setObject(p++, idx != null ? row[idx] : null);
                    }
                    for (String pk : primaryKeys) {
                        Integer idx = colIndex.get(pk.toLowerCase(Locale.ROOT));
                        updateStmt.setObject(p++, idx != null ? row[idx] : null);
                    }
                    updateStmt.addBatch();
                }
                updateStmt.executeBatch();
                updateStmt.clearBatch();
            }
            if (!insertBuf.isEmpty()) {
                for (Object[] row : insertBuf) {
                    for (int i = 0; i < row.length; i++) {
                        insertStmt.setObject(i + 1, row[i]);
                    }
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
                insertStmt.clearBatch();
                insertBuf.clear();
            }
            tgt.commit();
            touchWatchdog();
        } catch (SQLException e) {
            safeRollback(tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
            }
            throw e;
        }
    }

    private static Map<String, Integer> colIndexMap(List<String> columns) {
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            colIndex.put(columns.get(i).toLowerCase(Locale.ROOT), i);
        }
        return colIndex;
    }

    private long maybeLogDataSyncProgress(String tableTag, long scanned, long inserted, long updated, long deleted,
                                          long start, long lastLogAt, SyncTask task, AtomicInteger lastSaveTime) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < PROGRESS_LOG_INTERVAL_SEC * 1000L) {
            return lastLogAt;
        }
        long elapsedSec = Math.max(1, (now - start) / 1000);
        String heartbeat = "【心跳】仍在运行 · 差异同步 扫描=" + scanned
                + " +" + inserted + " ~" + updated + " -" + deleted + ", 用时 " + elapsedSec + "s";
        log.info("{} {}", tableTag, heartbeat);
        updateTaskDetail(task, lastSaveTime, tableTag + " · 同步 +" + inserted + "/~" + updated + "/-" + deleted);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " " + heartbeat);
        }
        return now;
    }

    /**
     * 按主键增量合并：不清空目标表。
     * MySQL/GoldenDB 使用 INSERT … ON DUPLICATE KEY UPDATE；其他库对每行先 UPDATE 再必要时 INSERT。
     */
    private long batchUpsertByPrimaryKey(WorkConns conns,
                                         String srcQualified, String tgtQualified,
                                         List<String> columns, List<String> primaryKeys,
                                         DatabaseType sourceDbType, DatabaseType targetDbType,
                                         int batchSize, String tableName, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        String selectColumns = quoteColumnList(columns, sourceDbType);
        String selectSql = "SELECT " + selectColumns + " FROM " + srcQualified;
        final String upsertSql;
        final boolean nativeUpsert = SyncUpsertHelper.supportsNativeUpsert(targetDbType);
        if (nativeUpsert) {
            upsertSql = SyncUpsertHelper.buildMysqlOnDuplicateUpsert(
                    tgtQualified, columns, primaryKeys, targetDbType);
        } else {
            upsertSql = null;
        }

        log.info("{} [源库 SELECT] {}", tableName, shortenSql(selectSql, 400));
        if (nativeUpsert) {
            log.info("{} [目标库 UPSERT] {}", tableName, shortenSql(upsertSql, 500));
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "info", tableName + " [源 SELECT] " + shortenSql(selectSql, 400));
                uiLog(task.getId(), "info", tableName + " [目标 UPSERT] " + shortenSql(upsertSql, 500));
            }
        } else {
            log.info("{} [目标库 UPSERT] 回退为逐行 UPDATE/INSERT（库类型={}）", tableName, targetDbType);
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "info", tableName + " [目标 UPSERT] 逐行 UPDATE/INSERT");
            }
        }

        Connection sourceConn = conns.src;
        Connection targetConn = conns.tgt;
        sourceConn.setAutoCommit(true);
        targetConn.setAutoCommit(false);

        long totalRows = 0;
        long copyStart = System.currentTimeMillis();
        ProgressLogClock progressClock = new ProgressLogClock(copyStart);
        int committedBatches = 0;
        int pendingLimit = Math.min(Math.max(1, batchSize), MAX_INSERT_PENDING_ROWS);
        List<Object[]> pending = new ArrayList<>(Math.min(pendingLimit, 4096));
        PreparedStatement upsertStmt = null;
        PreparedStatement updateStmt = null;
        PreparedStatement insertStmt = null;

        try (PreparedStatement selectStmt = sourceConn.prepareStatement(selectSql)) {
            try {
                selectStmt.setMaxRows(0);
            } catch (SQLException ignored) {
            }
            if (sourceDbType == DatabaseType.MYSQL || sourceDbType == DatabaseType.GOLDENDB) {
                selectStmt.setFetchSize(Integer.MIN_VALUE);
            } else {
                selectStmt.setFetchSize(batchSize);
            }

            if (nativeUpsert) {
                upsertStmt = targetConn.prepareStatement(upsertSql);
            } else {
                updateStmt = targetConn.prepareStatement(
                        buildGenericUpdateSql(tgtQualified, columns, primaryKeys, targetDbType));
                insertStmt = targetConn.prepareStatement(
                        buildGenericInsertSql(tgtQualified, columns, targetDbType));
            }

            log.info("{} 开始在源库执行查询: {}", tableName, srcQualified);
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    if (totalRows % 2000 == 0) {
                        touchWatchdog();
                        checkCancel(task.getId(), "upsert", false);
                    }
                    Object[] row = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    pending.add(row);
                    totalRows++;

                    if (pending.size() >= pendingLimit) {
                        if (nativeUpsert) {
                            upsertStmt = flushInsertPending(conns, upsertStmt, upsertSql, pending, tableName);
                            committedBatches++;
                            upsertStmt = maybeRecycleTargetDuringInsert(
                                    conns, upsertStmt, upsertSql, tableName, committedBatches);
                        } else {
                            flushGenericUpsert(conns, updateStmt, insertStmt, columns, primaryKeys, pending, tableName);
                            committedBatches++;
                        }
                        targetConn = conns.tgt;
                        maybeLogCopyProgress(tableName, totalRows, committedBatches, copyStart,
                                progressClock, task, lastSaveTime);
                    }
                }

                if (!pending.isEmpty()) {
                    if (nativeUpsert) {
                        upsertStmt = flushInsertPending(conns, upsertStmt, upsertSql, pending, tableName);
                    } else {
                        flushGenericUpsert(conns, updateStmt, insertStmt, columns, primaryKeys, pending, tableName);
                    }
                    committedBatches++;
                }
            }
        } catch (SQLException e) {
            safeRollback(conns.tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
            }
            throw e;
        } finally {
            closeQuietly(upsertStmt);
            closeQuietly(updateStmt);
            closeQuietly(insertStmt);
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - copyStart) / 1000);
        log.info("{} 合并写入结束: {} 行, {} 批, 耗时 {}s", tableName, totalRows, committedBatches, elapsedSec);
        warnIfSuspiciousSelectCap(tableName, totalRows, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 合并写入结束: " + totalRows + " 行, "
                    + committedBatches + " 批, 耗时 " + elapsedSec + "s");
        }
        return totalRows;
    }

    private static void closeQuietly(PreparedStatement ps) {
        if (ps == null) {
            return;
        }
        try {
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private static void closeQuietly(ResultSet rs) {
        if (rs == null) {
            return;
        }
        try {
            rs.close();
        } catch (SQLException ignored) {
        }
    }

    private String buildGenericInsertSql(String qualified, List<String> columns, DatabaseType dbType) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        return "INSERT INTO " + qualified + " (" + quoteColumnList(columns, dbType) + ") VALUES (" + placeholders + ")";
    }

    private String buildGenericUpdateSql(String qualified, List<String> columns, List<String> primaryKeys,
                                         DatabaseType dbType) {
        List<String> nonPk = SyncUpsertHelper.nonPrimaryColumns(columns, primaryKeys);
        if (nonPk.isEmpty()) {
            // 仅主键：UPDATE 自身，用于探测是否存在
            String pk0 = DatabaseDialect.escapeIdentifier(primaryKeys.get(0), dbType);
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < primaryKeys.size(); i++) {
                if (i > 0) {
                    where.append(" AND ");
                }
                where.append(DatabaseDialect.escapeIdentifier(primaryKeys.get(i), dbType)).append("=?");
            }
            return "UPDATE " + qualified + " SET " + pk0 + "=" + pk0 + " WHERE " + where;
        }
        StringBuilder set = new StringBuilder();
        for (int i = 0; i < nonPk.size(); i++) {
            if (i > 0) {
                set.append(", ");
            }
            set.append(DatabaseDialect.escapeIdentifier(nonPk.get(i), dbType)).append("=?");
        }
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            where.append(DatabaseDialect.escapeIdentifier(primaryKeys.get(i), dbType)).append("=?");
        }
        return "UPDATE " + qualified + " SET " + set + " WHERE " + where;
    }

    /**
     * 非 MySQL 族：逐行 UPDATE，影响 0 行则 INSERT。
     */
    private void flushGenericUpsert(WorkConns conns, PreparedStatement updateStmt, PreparedStatement insertStmt,
                                    List<String> columns, List<String> primaryKeys,
                                    List<Object[]> pending, String tableTag) throws SQLException {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            colIndex.put(columns.get(i).toLowerCase(Locale.ROOT), i);
        }
        List<String> nonPk = SyncUpsertHelper.nonPrimaryColumns(columns, primaryKeys);
        Connection tgt = conns.tgt;
        try {
            for (Object[] row : pending) {
                int p = 1;
                for (String col : nonPk) {
                    Integer idx = colIndex.get(col.toLowerCase(Locale.ROOT));
                    updateStmt.setObject(p++, idx != null ? row[idx] : null);
                }
                for (String pk : primaryKeys) {
                    Integer idx = colIndex.get(pk.toLowerCase(Locale.ROOT));
                    updateStmt.setObject(p++, idx != null ? row[idx] : null);
                }
                int updated = updateStmt.executeUpdate();
                if (updated == 0) {
                    for (int i = 0; i < row.length; i++) {
                        insertStmt.setObject(i + 1, row[i]);
                    }
                    insertStmt.executeUpdate();
                }
            }
            tgt.commit();
            pending.clear();
        } catch (SQLException e) {
            safeRollback(tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
            }
            throw e;
        }
    }

    /**
     * 批量复制数据。有主键时按 keyset 分块翻页（千万级推荐）；无主键时回退流式 SELECT。
     * 超大表（估算≥{@link SyncIdleWatchdog#STREAM_CHANNEL_ROW_THRESHOLD} 或已标超大）走 DataX 式读→通道→写。
     * 源连接只读且 autoCommit=true；目标连接按批 commit。
     */
    private long batchCopyData(WorkConns conns,
                               String srcQualified, String tgtQualified,
                               List<String> columns, List<String> primaryKeys,
                               DatabaseType sourceDbType, DatabaseType targetDbType,
                               int batchSize, String tableName, SyncTask task, AtomicInteger lastSaveTime,
                               boolean incremental, long estimatedRows)
            throws SQLException, SyncAbortException {
        String selectColumns = quoteColumnList(columns, sourceDbType);
        String insertColumns = quoteColumnList(columns, targetDbType);

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }
        String insertKeyword = "INSERT INTO ";
        if (incremental && (targetDbType == DatabaseType.MYSQL || targetDbType == DatabaseType.GOLDENDB)) {
            insertKeyword = "INSERT IGNORE INTO ";
        }
        final String insertSql = insertKeyword + tgtQualified + " (" + insertColumns + ") VALUES (" + placeholders + ")";

        String insertPreview = shortenSql(insertSql, 400);
        log.info("{} [目标库 INSERT] {}", tableName, insertPreview);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " [目标 INSERT] " + insertPreview);
        }

        boolean streamChannel = shouldUseStreamChannel(tableName, estimatedRows);
        if (streamChannel) {
            maybeMarkLargeTable(tableName, Math.max(estimatedRows, SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD),
                    task, SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD);
        }
        if (canUseKeysetPaging(columns, primaryKeys, sourceDbType)) {
            if (streamChannel) {
                return batchCopyDataByKeysetChannel(conns, srcQualified, columns, primaryKeys, selectColumns,
                        insertSql, sourceDbType, batchSize, tableName, task, lastSaveTime, incremental);
            }
            return batchCopyDataByKeyset(conns, srcQualified, columns, primaryKeys, selectColumns,
                    insertSql, sourceDbType, batchSize, tableName, task, lastSaveTime, incremental);
        }
        log.warn("{} 无可用主键或不支持 keyset，回退长流式 SELECT（千万级表易在协议异常时整表失败）", tableName);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "warn", tableName + " 无主键分块，使用流式读取（大表风险较高）");
        }
        if (streamChannel) {
            return batchCopyDataStreamingChannel(conns, srcQualified, columns, selectColumns, insertSql,
                    sourceDbType, batchSize, tableName, task, lastSaveTime, incremental);
        }
        return batchCopyDataStreaming(conns, srcQualified, columns, selectColumns, insertSql,
                sourceDbType, batchSize, tableName, task, lastSaveTime, incremental);
    }

    /** 估算或已标记超大表时启用 DataX 通道流式写。 */
    private boolean shouldUseStreamChannel(String tableTag, long estimatedRows) {
        if (estimatedRows >= SyncIdleWatchdog.STREAM_CHANNEL_ROW_THRESHOLD) {
            return true;
        }
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        return wd != null && wd.isLargeTable(tableTag);
    }

    /**
     * 主键分块：每页短查询 + 写入后可重借连接，协议损坏只重试当前页，不拖死流式 ResultSet。
     */
    private long batchCopyDataByKeyset(WorkConns conns,
                                       String srcQualified,
                                       List<String> columns, List<String> primaryKeys,
                                       String selectColumns, String insertSql,
                                       DatabaseType sourceDbType, int batchSize, String tableName,
                                       SyncTask task, AtomicInteger lastSaveTime, boolean incremental)
            throws SQLException, SyncAbortException {
        int[] pkIndexes = resolvePkIndexes(columns, primaryKeys);
        List<String> pkQuoted = new ArrayList<>(primaryKeys.size());
        for (String pk : primaryKeys) {
            pkQuoted.add(DatabaseDialect.escapeIdentifier(pk, sourceDbType));
        }

        conns.src.setAutoCommit(true);
        conns.tgt.setAutoCommit(false);

        long totalRows = 0;
        long copyStart = System.currentTimeMillis();
        ProgressLogClock progressClock = new ProgressLogClock(copyStart);
        int committedBatches = 0;
        int pages = 0;
        int pendingLimit = Math.min(Math.max(1, batchSize), MAX_INSERT_PENDING_ROWS);
        Object[] lastPk = null;
        PreparedStatement insertStmt = null;
        String activeInsertSql = insertSql;

        log.info("{} 主键分块写入: pk={}, 每页≤{}行, 积压≤{}, execute≤{}",
                tableName, primaryKeys, KEYSET_PAGE_ROWS, pendingLimit, MAX_EXECUTE_BATCH_CHUNK);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 主键分块写入（每页≤" + KEYSET_PAGE_ROWS
                    + "，切块≤" + MAX_EXECUTE_BATCH_CHUNK + "）pk=" + String.join(",", primaryKeys));
        }

        try {
            insertStmt = conns.tgt.prepareStatement(activeInsertSql);
            while (true) {
                touchWatchdog();
                checkCancel(task.getId(), "insert", !incremental);

                List<Object[]> page = fetchKeysetPage(conns.src, srcQualified, selectColumns, pkQuoted,
                        lastPk, KEYSET_PAGE_ROWS, columns.size());
                if (page.isEmpty()) {
                    break;
                }
                pages++;

                // 页级重试：flush 耗尽后重借源+目标，剩余行改 INSERT IGNORE 再写
                SQLException pageFail = null;
                for (int pageAttempt = 1; pageAttempt <= KEYSET_PAGE_MAX_ATTEMPTS; pageAttempt++) {
                    try {
                        List<Object[]> pending = new ArrayList<>(page);
                        while (!pending.isEmpty()) {
                            int take = Math.min(pendingLimit, pending.size());
                            List<Object[]> chunk = new ArrayList<>(pending.subList(0, take));
                            pending.subList(0, take).clear();
                            insertStmt = flushInsertPending(conns, insertStmt, activeInsertSql, chunk, tableName);
                            committedBatches++;
                            insertStmt = maybeRecycleTargetDuringInsert(
                                    conns, insertStmt, activeInsertSql, tableName, committedBatches);
                        }
                        pageFail = null;
                        break;
                    } catch (SQLException e) {
                        pageFail = e;
                        if (!isRetriableJdbcFailure(e) && !isPacketTooBigFailure(e) && !isDuplicateKeyFailure(e)) {
                            throw e;
                        }
                        if (pageAttempt >= KEYSET_PAGE_MAX_ATTEMPTS) {
                            throw e;
                        }
                        log.warn("{} 主键页写入失败，重借连接后整页重试 {}/{}: {}",
                                tableName, pageAttempt, KEYSET_PAGE_MAX_ATTEMPTS, summarizeJdbcError(e));
                        if (task != null && task.getId() != null) {
                            uiLog(task.getId(), "warn", tableName + " 主键页写入异常，重试 "
                                    + pageAttempt + "/" + KEYSET_PAGE_MAX_ATTEMPTS + ": " + summarizeJdbcError(e));
                        }
                        try {
                            if (insertStmt != null) {
                                insertStmt.close();
                            }
                        } catch (SQLException ignored) {
                        }
                        insertStmt = null;
                        activeInsertSql = toSafeRetryInsertSql(insertSql);
                        conns.reborrowTargetAfterError(e, true);
                        conns.reborrowSourceAfterError(e, true);
                        conns.src.setAutoCommit(true);
                        conns.tgt.setAutoCommit(false);
                        insertStmt = conns.tgt.prepareStatement(activeInsertSql);
                    }
                }
                if (pageFail != null) {
                    throw pageFail;
                }

                lastPk = extractPkValues(page.get(page.size() - 1), pkIndexes);
                totalRows += page.size();
                maybeLogCopyProgress(tableName, totalRows, committedBatches, copyStart,
                        progressClock, task, lastSaveTime);

                if (pages % KEYSET_RECYCLE_EVERY_PAGES == 0) {
                    log.info("{} 已完成 {} 页/{} 行，重借源+目标连接", tableName, pages, totalRows);
                    if (insertStmt != null) {
                        try {
                            insertStmt.close();
                        } catch (SQLException ignored) {
                        }
                    }
                    conns.recycleAfterLongPhase(tableName);
                    conns.src.setAutoCommit(true);
                    conns.tgt.setAutoCommit(false);
                    insertStmt = conns.tgt.prepareStatement(activeInsertSql);
                }
            }
        } catch (SQLException e) {
            safeRollback(conns.tgt);
            if (isRetriableJdbcFailure(e)) {
                // 流式结果集异常时源连接可能已坏，剔除；目标仅在连通性问题时剔除
                if (shouldEvictAfterError(e, conns.src)) {
                    conns.discardSource();
                }
                if (shouldEvictAfterError(e, conns.tgt)) {
                    conns.discardTarget();
                }
            }
            String hint = "phase=insert（主键分块）。已写入约 " + totalRows + " 行后失败。"
                    + "数据库协议/连接异常时请降低并行或批次，并对该表重跑（建议增量 INSERT IGNORE）。";
            throw enrichSyncSqlException(e, hint);
        } finally {
            if (insertStmt != null) {
                try {
                    insertStmt.close();
                } catch (SQLException ignored) {
                }
            }
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - copyStart) / 1000);
        log.info("{} 写入结束(keyset): {} 行, {} 页, {} 批, 耗时 {}s",
                tableName, totalRows, pages, committedBatches, elapsedSec);
        warnIfSuspiciousSelectCap(tableName, totalRows, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 写入结束: " + totalRows + " 行, "
                    + pages + " 页, " + committedBatches + " 批, 耗时 " + elapsedSec + "s");
        }
        return totalRows;
    }

    /**
     * DataX 式主键分块：读线程翻页投递有界通道，写线程流式 batch INSERT（源/目标连接分属不同线程）。
     */
    private long batchCopyDataByKeysetChannel(WorkConns conns,
                                              String srcQualified,
                                              List<String> columns, List<String> primaryKeys,
                                              String selectColumns, String insertSql,
                                              DatabaseType sourceDbType, int batchSize, String tableName,
                                              SyncTask task, AtomicInteger lastSaveTime, boolean incremental)
            throws SQLException, SyncAbortException {
        int[] pkIndexes = resolvePkIndexes(columns, primaryKeys);
        List<String> pkQuoted = new ArrayList<>(primaryKeys.size());
        for (String pk : primaryKeys) {
            pkQuoted.add(DatabaseDialect.escapeIdentifier(pk, sourceDbType));
        }

        conns.src.setAutoCommit(true);
        conns.tgt.setAutoCommit(false);

        int pendingLimit = Math.min(Math.max(1, batchSize), MAX_INSERT_PENDING_ROWS);
        long copyStart = System.currentTimeMillis();
        AtomicLong totalRows = new AtomicLong();
        AtomicInteger committedBatches = new AtomicInteger();
        AtomicInteger pages = new AtomicInteger();
        ProgressLogClock progressClock = new ProgressLogClock(copyStart);

        SyncRecordChannel channel = new SyncRecordChannel(STREAM_CHANNEL_CAPACITY);
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        final String tableTag = tableName;

        log.info("{} DataX流式写入(keyset): pk={}, 页≤{}, 通道容量={}, 积压≤{}, execute≤{}",
                tableName, primaryKeys, KEYSET_PAGE_ROWS, STREAM_CHANNEL_CAPACITY,
                pendingLimit, MAX_EXECUTE_BATCH_CHUNK);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 【DataX流式】读→通道→写（主键分块，通道容量="
                    + STREAM_CHANNEL_CAPACITY + "，每页≤" + KEYSET_PAGE_ROWS + "）");
        }

        Thread writer = new Thread(() -> {
            TL_WATCHDOG.set(wd);
            TL_TABLE_TAG.set(tableTag);
            PreparedStatement insertStmt = null;
            String activeInsertSql = insertSql;
            try {
                insertStmt = conns.tgt.prepareStatement(activeInsertSql);
                while (true) {
                    Object item = channel.take();
                    if (item == SyncRecordChannel.END) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    List<Object[]> page = (List<Object[]>) item;
                    List<Object[]> pending = new ArrayList<>(page);
                    while (!pending.isEmpty()) {
                        touchWatchdog();
                        int take = Math.min(pendingLimit, pending.size());
                        List<Object[]> chunk = new ArrayList<>(pending.subList(0, take));
                        pending.subList(0, take).clear();
                        insertStmt = flushInsertPending(conns, insertStmt, activeInsertSql, chunk, tableName);
                        int batches = committedBatches.incrementAndGet();
                        insertStmt = maybeRecycleTargetDuringInsert(
                                conns, insertStmt, activeInsertSql, tableName, batches);
                    }
                    long written = totalRows.addAndGet(page.size());
                    maybeLogCopyProgress(tableName, written, committedBatches.get(), copyStart,
                            progressClock, task, lastSaveTime);
                }
            } catch (Throwable t) {
                channel.fail(t);
                channel.clear();
                safeRollback(conns.tgt);
            } finally {
                closeQuietly(insertStmt);
                TL_WATCHDOG.remove();
                TL_TABLE_TAG.remove();
            }
        }, "sync-datax-writer-" + sanitizeThreadName(tableName));
        writer.setDaemon(true);
        writer.start();

        try {
            Object[] lastPk = null;
            while (true) {
                channel.throwIfFailed();
                touchWatchdog();
                checkCancel(task.getId(), "insert", !incremental);

                List<Object[]> page = fetchKeysetPage(conns.src, srcQualified, selectColumns, pkQuoted,
                        lastPk, KEYSET_PAGE_ROWS, columns.size());
                if (page.isEmpty()) {
                    break;
                }
                pages.incrementAndGet();
                lastPk = extractPkValues(page.get(page.size() - 1), pkIndexes);
                channel.putBatch(page);
            }
            channel.putEnd();
            writer.join();
            channel.throwIfFailed();
        } catch (SyncAbortException e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            throw e;
        } catch (SQLException e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            if (isRetriableJdbcFailure(e)) {
                if (shouldEvictAfterError(e, conns.src)) {
                    conns.discardSource();
                }
                if (shouldEvictAfterError(e, conns.tgt)) {
                    conns.discardTarget();
                }
            }
            throw enrichSyncSqlException(e,
                    "phase=insert（DataX keyset 通道）。已写入约 " + totalRows.get() + " 行后失败。");
        } catch (Exception e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            Throwable cause = channel.error() != null ? channel.error() : e;
            if (cause instanceof SyncAbortException) {
                throw (SyncAbortException) cause;
            }
            if (cause instanceof SQLException) {
                throw enrichSyncSqlException((SQLException) cause,
                        "phase=insert（DataX keyset 通道）。已写入约 " + totalRows.get() + " 行后失败。");
            }
            throw new SQLException("DataX 通道写入失败: " + cause.getMessage(), cause);
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - copyStart) / 1000);
        long rows = totalRows.get();
        log.info("{} 写入结束(DataX·keyset): {} 行, {} 页, {} 批, 耗时 {}s",
                tableName, rows, pages.get(), committedBatches.get(), elapsedSec);
        warnIfSuspiciousSelectCap(tableName, rows, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 写入结束(DataX流式): " + rows + " 行, "
                    + pages.get() + " 页, " + committedBatches.get() + " 批, 耗时 " + elapsedSec + "s");
        }
        return rows;
    }

    private static void joinWriterQuietly(Thread writer) {
        try {
            writer.join(30_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitizeThreadName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return "table";
        }
        String s = tableName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private List<Object[]> fetchKeysetPage(Connection sourceConn, String srcQualified, String selectColumns,
                                           List<String> pkQuoted, Object[] lastPk, int pageSize, int columnCount)
            throws SQLException {
        touchWatchdog();
        boolean first = lastPk == null;
        String sql = buildKeysetSelectSql(selectColumns, srcQualified, pkQuoted, first);
        List<Object[]> rows = new ArrayList<>(Math.min(pageSize, 1024));
        try (PreparedStatement ps = sourceConn.prepareStatement(sql)) {
            try {
                ps.setMaxRows(0);
            } catch (SQLException ignored) {
            }
            ps.setFetchSize(Math.min(pageSize, 1000));
            int idx = 1;
            if (!first) {
                for (Object v : lastPk) {
                    ps.setObject(idx++, v);
                }
            }
            ps.setInt(idx, pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] row = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 无主键时的流式复制（保留兼容；千万级不推荐）。 */
    private long batchCopyDataStreaming(WorkConns conns,
                                        String srcQualified,
                                        List<String> columns, String selectColumns, String insertSql,
                                        DatabaseType sourceDbType, int batchSize, String tableName,
                                        SyncTask task, AtomicInteger lastSaveTime, boolean incremental)
            throws SQLException, SyncAbortException {
        String selectSql = "SELECT " + selectColumns + " FROM " + srcQualified;
        String selectPreview = shortenSql(selectSql, 400);
        log.info("{} [源库 SELECT·流式] {}", tableName, selectPreview);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " [源 SELECT] " + selectPreview);
        }

        Connection sourceConn = conns.src;
        Connection targetConn = conns.tgt;
        sourceConn.setAutoCommit(true);
        targetConn.setAutoCommit(false);

        long totalRows = 0;
        long copyStart = System.currentTimeMillis();
        ProgressLogClock progressClock = new ProgressLogClock(copyStart);
        int committedBatches = 0;
        int pendingLimit = Math.min(Math.max(1, batchSize), MAX_INSERT_PENDING_ROWS);
        List<Object[]> pending = new ArrayList<>(Math.min(pendingLimit, 4096));
        PreparedStatement insertStmt = null;

        try (PreparedStatement selectStmt = sourceConn.prepareStatement(selectSql)) {
            try {
                selectStmt.setMaxRows(0);
            } catch (SQLException ignored) {
            }
            if (sourceDbType == DatabaseType.MYSQL || sourceDbType == DatabaseType.GOLDENDB) {
                selectStmt.setFetchSize(Integer.MIN_VALUE);
            } else {
                selectStmt.setFetchSize(batchSize);
            }

            insertStmt = targetConn.prepareStatement(insertSql);
            log.info("{} 开始流式查询: {}（积压≤{}，切块≤{}）",
                    tableName, srcQualified, pendingLimit, MAX_EXECUTE_BATCH_CHUNK);
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    if (totalRows % 2000 == 0) {
                        touchWatchdog();
                        checkCancel(task.getId(), "insert", !incremental);
                    }
                    Object[] row = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    pending.add(row);
                    totalRows++;

                    if (pending.size() >= pendingLimit) {
                        insertStmt = flushInsertPending(conns, insertStmt, insertSql, pending, tableName);
                        committedBatches++;
                        insertStmt = maybeRecycleTargetDuringInsert(
                                conns, insertStmt, insertSql, tableName, committedBatches);
                        maybeLogCopyProgress(tableName, totalRows, committedBatches, copyStart,
                                progressClock, task, lastSaveTime);
                    }
                }

                if (!pending.isEmpty()) {
                    insertStmt = flushInsertPending(conns, insertStmt, insertSql, pending, tableName);
                    committedBatches++;
                }
            }
        } catch (SQLException e) {
            safeRollback(conns.tgt);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
            }
            throw enrichSyncSqlException(e,
                    "phase=insert（流式）。数据库协议/连接异常（GoldenDB 高并发批写常见）。"
                            + "已丢弃坏连接；请对该表重跑或降低并行/批次。建议为表建主键以启用分块翻页。");
        } finally {
            if (insertStmt != null) {
                try {
                    insertStmt.close();
                } catch (SQLException ignored) {
                }
            }
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - copyStart) / 1000);
        log.info("{} 写入结束(流式): {} 行, {} 批, 耗时 {}s", tableName, totalRows, committedBatches, elapsedSec);
        warnIfSuspiciousSelectCap(tableName, totalRows, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 写入结束: " + totalRows + " 行, "
                    + committedBatches + " 批, 耗时 " + elapsedSec + "s");
        }
        return totalRows;
    }

    /**
     * DataX 式无主键流式：读线程扫 ResultSet 投递通道，写线程 batch INSERT（读写下并行）。
     */
    private long batchCopyDataStreamingChannel(WorkConns conns,
                                               String srcQualified,
                                               List<String> columns, String selectColumns, String insertSql,
                                               DatabaseType sourceDbType, int batchSize, String tableName,
                                               SyncTask task, AtomicInteger lastSaveTime, boolean incremental)
            throws SQLException, SyncAbortException {
        String selectSql = "SELECT " + selectColumns + " FROM " + srcQualified;
        String selectPreview = shortenSql(selectSql, 400);
        log.info("{} [源库 SELECT·DataX流式] {}", tableName, selectPreview);

        conns.src.setAutoCommit(true);
        conns.tgt.setAutoCommit(false);

        int pendingLimit = Math.min(Math.max(1, batchSize), MAX_INSERT_PENDING_ROWS);
        long copyStart = System.currentTimeMillis();
        AtomicLong totalRows = new AtomicLong();
        AtomicInteger committedBatches = new AtomicInteger();
        ProgressLogClock progressClock = new ProgressLogClock(copyStart);

        SyncRecordChannel channel = new SyncRecordChannel(STREAM_CHANNEL_CAPACITY);
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        final String tableTag = tableName;

        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 【DataX流式】读→通道→写（无主键长游标，通道容量="
                    + STREAM_CHANNEL_CAPACITY + "）");
        }

        Thread writer = new Thread(() -> {
            TL_WATCHDOG.set(wd);
            TL_TABLE_TAG.set(tableTag);
            PreparedStatement insertStmt = null;
            try {
                insertStmt = conns.tgt.prepareStatement(insertSql);
                while (true) {
                    Object item = channel.take();
                    if (item == SyncRecordChannel.END) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    List<Object[]> chunk = (List<Object[]>) item;
                    insertStmt = flushInsertPending(conns, insertStmt, insertSql, chunk, tableName);
                    int batches = committedBatches.incrementAndGet();
                    insertStmt = maybeRecycleTargetDuringInsert(
                            conns, insertStmt, insertSql, tableName, batches);
                    long written = totalRows.addAndGet(chunk.size());
                    maybeLogCopyProgress(tableName, written, committedBatches.get(), copyStart,
                            progressClock, task, lastSaveTime);
                }
            } catch (Throwable t) {
                channel.fail(t);
                channel.clear();
                safeRollback(conns.tgt);
            } finally {
                closeQuietly(insertStmt);
                TL_WATCHDOG.remove();
                TL_TABLE_TAG.remove();
            }
        }, "sync-datax-writer-" + sanitizeThreadName(tableName));
        writer.setDaemon(true);
        writer.start();

        List<Object[]> pending = new ArrayList<>(Math.min(pendingLimit, 4096));
        try (PreparedStatement selectStmt = conns.src.prepareStatement(selectSql)) {
            try {
                selectStmt.setMaxRows(0);
            } catch (SQLException ignored) {
            }
            if (sourceDbType == DatabaseType.MYSQL || sourceDbType == DatabaseType.GOLDENDB) {
                selectStmt.setFetchSize(Integer.MIN_VALUE);
            } else {
                selectStmt.setFetchSize(batchSize);
            }
            long scanned = 0;
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    channel.throwIfFailed();
                    if (scanned % 2000 == 0) {
                        touchWatchdog();
                        checkCancel(task.getId(), "insert", !incremental);
                    }
                    Object[] row = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    pending.add(row);
                    scanned++;
                    if (pending.size() >= pendingLimit) {
                        channel.putBatch(new ArrayList<>(pending));
                        pending.clear();
                    }
                }
            }
            if (!pending.isEmpty()) {
                channel.putBatch(pending);
                pending = new ArrayList<>();
            }
            channel.putEnd();
            writer.join();
            channel.throwIfFailed();
        } catch (SyncAbortException e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            throw e;
        } catch (SQLException e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            if (isRetriableJdbcFailure(e)) {
                conns.discardTarget();
            }
            throw enrichSyncSqlException(e,
                    "phase=insert（DataX 流式通道）。已写入约 " + totalRows.get() + " 行后失败。");
        } catch (Exception e) {
            channel.fail(e);
            channel.clear();
            writer.interrupt();
            joinWriterQuietly(writer);
            Throwable cause = channel.error() != null ? channel.error() : e;
            if (cause instanceof SyncAbortException) {
                throw (SyncAbortException) cause;
            }
            if (cause instanceof SQLException) {
                throw enrichSyncSqlException((SQLException) cause,
                        "phase=insert（DataX 流式通道）。已写入约 " + totalRows.get() + " 行后失败。");
            }
            throw new SQLException("DataX 通道写入失败: " + cause.getMessage(), cause);
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - copyStart) / 1000);
        long rows = totalRows.get();
        log.info("{} 写入结束(DataX·流式): {} 行, {} 批, 耗时 {}s",
                tableName, rows, committedBatches.get(), elapsedSec);
        warnIfSuspiciousSelectCap(tableName, rows, task);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableName + " 写入结束(DataX流式): " + rows + " 行, "
                    + committedBatches.get() + " 批, 耗时 " + elapsedSec + "s");
        }
        return rows;
    }

    static boolean canUseKeysetPaging(List<String> columns, List<String> primaryKeys, DatabaseType sourceDbType) {
        if (columns == null || primaryKeys == null || primaryKeys.isEmpty()) {
            return false;
        }
        if (sourceDbType != DatabaseType.MYSQL && sourceDbType != DatabaseType.GOLDENDB
                && sourceDbType != DatabaseType.POSTGRESQL && sourceDbType != DatabaseType.H2) {
            return false;
        }
        for (String pk : primaryKeys) {
            if (pk == null || pk.trim().isEmpty()) {
                return false;
            }
            if (indexOfIgnoreCase(columns, pk.trim()) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造主键翻页 SELECT。firstPage=true 时无 WHERE；否则 {@code (pk…) > (?…)}。
     */
    static String buildKeysetSelectSql(String selectColumns, String srcQualified,
                                       List<String> pkQuoted, boolean firstPage) {
        String orderBy = String.join(", ", pkQuoted);
        if (firstPage) {
            return "SELECT " + selectColumns + " FROM " + srcQualified
                    + " ORDER BY " + orderBy + " LIMIT ?";
        }
        StringBuilder pkList = new StringBuilder();
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < pkQuoted.size(); i++) {
            if (i > 0) {
                pkList.append(", ");
                ph.append(", ");
            }
            pkList.append(pkQuoted.get(i));
            ph.append("?");
        }
        return "SELECT " + selectColumns + " FROM " + srcQualified
                + " WHERE (" + pkList + ") > (" + ph + ")"
                + " ORDER BY " + orderBy + " LIMIT ?";
    }

    static int[] resolvePkIndexes(List<String> columns, List<String> primaryKeys) {
        int[] idx = new int[primaryKeys.size()];
        for (int i = 0; i < primaryKeys.size(); i++) {
            int at = indexOfIgnoreCase(columns, primaryKeys.get(i));
            if (at < 0) {
                throw new IllegalArgumentException("主键列不在 SELECT 列中: " + primaryKeys.get(i));
            }
            idx[i] = at;
        }
        return idx;
    }

    static Object[] extractPkValues(Object[] row, int[] pkIndexes) {
        Object[] pk = new Object[pkIndexes.length];
        for (int i = 0; i < pkIndexes.length; i++) {
            pk[i] = row[pkIndexes[i]];
        }
        return pk;
    }

    private static int indexOfIgnoreCase(List<String> columns, String name) {
        if (columns == null || name == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static SQLException enrichSyncSqlException(SQLException e, String hint) {
        if (e == null) {
            return new SQLException(hint);
        }
        String base = e.getMessage();
        if (base == null || base.trim().isEmpty()) {
            base = e.getClass().getSimpleName();
        }
        SQLException wrapped = new SQLException(base + " | " + hint, e.getSQLState(), e.getErrorCode());
        wrapped.initCause(e);
        return wrapped;
    }

    /**
     * 将 pending 行写入目标并 commit；协议/连接/包过大异常时丢弃坏连接、缩小批次重试。
     * 已成功 commit 的子批不会重放，避免 GoldenDB AIOOBE 重试时主键冲突（1062）。
     * @return 可用的 PreparedStatement（可能已重建）
     */
    private PreparedStatement flushInsertPending(WorkConns conns, PreparedStatement insertStmt,
                                                 String insertSql, List<Object[]> pending, String tableTag)
            throws SQLException {
        if (pending == null || pending.isEmpty()) {
            return insertStmt;
        }
        int chunk = Math.min(pending.size(), MAX_EXECUTE_BATCH_CHUNK);
        int committed = 0; // pending 中已成功提交的行数前缀
        String activeSql = insertSql;
        SQLException last = null;
        for (int attempt = 1; attempt <= INSERT_FLUSH_MAX_ATTEMPTS; attempt++) {
            try {
                Connection tgt = conns.tgt;
                if (tgt == null || tgt.isClosed()) {
                    conns.borrowTarget(true);
                    tgt = conns.tgt;
                    tgt.setAutoCommit(false);
                    if (insertStmt != null) {
                        try { insertStmt.close(); } catch (SQLException ignored) { }
                    }
                    insertStmt = tgt.prepareStatement(activeSql);
                }
                int i = committed;
                while (i < pending.size()) {
                    int end = Math.min(i + chunk, pending.size());
                    for (int j = i; j < end; j++) {
                        Object[] row = pending.get(j);
                        for (int c = 0; c < row.length; c++) {
                            insertStmt.setObject(c + 1, row[c]);
                        }
                        insertStmt.addBatch();
                    }
                    touchWatchdog();
                    SyncIdleWatchdog wd = TL_WATCHDOG.get();
                    if (wd != null) {
                        String tag = TL_TABLE_TAG.get();
                        if (wd.isTaskTimedOut() || wd.isTableTimedOut(tag)) {
                            throw new SQLException(wd.isTaskTimedOut()
                                    ? "整任务超时（无心跳），写入中止"
                                    : "表同步超时（无心跳），写入中止");
                        }
                    }
                    insertStmt.executeBatch();
                    insertStmt.clearBatch();
                    tgt.commit();
                    touchWatchdog();
                    committed = end;
                    i = end;
                }
                pending.clear();
                return insertStmt;
            } catch (SQLException e) {
                last = e;
                safeRollback(conns.tgt);
                boolean dup = isDuplicateKeyFailure(e);
                boolean packetTooBig = isPacketTooBigFailure(e);
                boolean retriable = isRetriableJdbcFailure(e) || packetTooBig
                        || (dup && attempt < INSERT_FLUSH_MAX_ATTEMPTS && committed < pending.size());
                if (!retriable || attempt >= INSERT_FLUSH_MAX_ATTEMPTS) {
                    throw e;
                }
                // 协议异常后服务端可能已写入：后续重试改 INSERT IGNORE，跳过已存在主键
                if (dup || attempt >= 1) {
                    String ignoreSql = toSafeRetryInsertSql(insertSql);
                    if (!ignoreSql.equals(activeSql)) {
                        activeSql = ignoreSql;
                        log.warn("{} 写入重试改用幂等 SQL，避免重复键: {}", tableTag, shortenSql(activeSql, 180));
                    }
                }
                if (packetTooBig) {
                    chunk = Math.max(50, Math.min(chunk / 2, 200));
                    log.warn("{} 写入包过大，切块降至 {} 后重试 {}/{}",
                            tableTag, chunk, attempt, INSERT_FLUSH_MAX_ATTEMPTS);
                } else {
                    chunk = Math.max(50, chunk / 2);
                }
                log.warn("{} 写入批次异常，已提交前缀={}/{}，丢弃连接并缩小批次后重试 {}/{}: {}",
                        tableTag, committed, pending.size(), attempt, INSERT_FLUSH_MAX_ATTEMPTS,
                        summarizeJdbcError(e));
                try {
                    if (insertStmt != null) {
                        insertStmt.close();
                    }
                } catch (SQLException ignored) {
                }
                insertStmt = null;
                conns.reborrowTargetAfterError(e, true);
                conns.tgt.setAutoCommit(false);
                insertStmt = conns.tgt.prepareStatement(activeSql);
            }
        }
        throw last != null ? last : new SQLException("写入批次失败");
    }

    /**
     * 百万级写入中途定期重借目标连接（不碰源侧流式 ResultSet），降低协议/连接老化导致的中断。
     */
    private PreparedStatement maybeRecycleTargetDuringInsert(WorkConns conns, PreparedStatement insertStmt,
                                                             String insertSql, String tableTag,
                                                             int committedBatches) throws SQLException {
        if (committedBatches <= 0 || committedBatches % INSERT_TARGET_RECYCLE_EVERY_FLUSHES != 0) {
            return insertStmt;
        }
        log.info("{} 已成功刷写 {} 批，重借目标连接（防百万级写入协议老化）", tableTag, committedBatches);
        if (insertStmt != null) {
            try {
                insertStmt.close();
            } catch (SQLException ignored) {
            }
        }
        conns.recycleTargetOnly(tableTag);
        conns.tgt.setAutoCommit(false);
        return conns.tgt.prepareStatement(insertSql);
    }

    static boolean isPacketTooBigFailure(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 12) {
            String cn = cur.getClass().getName();
            if (cn.contains("PacketTooBig") || cn.contains("CJPacketTooBig")) {
                return true;
            }
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                if (se.getErrorCode() == 1153 || se.getErrorCode() == 1105) {
                    return true;
                }
                String msg = se.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("packet") && (lower.contains("too large") || lower.contains("too big")
                            || lower.contains("max_allowed_packet"))) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /** 普通 INSERT 转为 INSERT IGNORE，供协议异常重试时幂等写入 */
    static String toSafeRetryInsertSql(String insertSql) {
        if (insertSql == null || insertSql.isEmpty()) {
            return insertSql;
        }
        String upper = insertSql.toUpperCase();
        if (upper.contains("INSERT IGNORE") || upper.contains("ON DUPLICATE KEY")
                || upper.contains("REPLACE INTO")) {
            return insertSql;
        }
        // 仅替换首个 INSERT INTO / INSERT
        if (upper.startsWith("INSERT INTO ")) {
            return "INSERT IGNORE INTO " + insertSql.substring("INSERT INTO ".length());
        }
        if (upper.startsWith("INSERT ")) {
            return "INSERT IGNORE " + insertSql.substring("INSERT ".length());
        }
        return insertSql;
    }

    static boolean isDuplicateKeyFailure(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                if (se.getErrorCode() == 1062) {
                    return true;
                }
                String state = se.getSQLState();
                if ("23000".equals(state)) {
                    String msg = se.getMessage();
                    if (msg != null) {
                        String lower = msg.toLowerCase();
                        if (lower.contains("duplicate") || lower.contains("unique constraint")) {
                            return true;
                        }
                    }
                }
                String msg = se.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("duplicate entry") || lower.contains("duplicate key")) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static void safeRollback(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                conn.rollback();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 清空目标表：仅 DML（分批 DELETE），不使用 TRUNCATE/DROP/CREATE。
     * 跳过 COUNT(*)，用首批 DELETE 探测是否为空，减少大表全表扫描耗时。
     * 清表期间只占用目标池连接；超长清表周期性重借，避免 wait_timeout。
     * @param tableTag 含库名与序号的日志标签
     */
    private void clearTable(WorkConns conns, String catalog, String tableName, String tableTag,
                            DatabaseType dbType, int insertBatchSize, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        String lockKey = targetClearLockKey(catalog, tableName);
        ReentrantLock clearLock = targetClearLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!clearLock.tryLock()) {
            log.warn("{} 等待同目标表清表锁释放（避免并行 DELETE LIMIT 互锁）: {}.{}",
                    tableTag, catalog, tableName);
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "warn", tableTag
                        + " 等待同目标表清表完成（并行 DELETE LIMIT 会争抢行锁）");
            }
            clearLock.lock();
        }
        try {
            doClearTable(conns, catalog, tableName, tableTag, dbType, insertBatchSize, task, lastSaveTime);
        } finally {
            clearLock.unlock();
            // 无人排队时移除锁对象，避免 targetClearLocks 无限增长
            if (!clearLock.hasQueuedThreads() && !clearLock.isLocked()) {
                targetClearLocks.remove(lockKey, clearLock);
            }
        }
    }

    /**
     * 按单列数值主键区间并行分批 DELETE；无可用 PK 时回退 {@link #clearTable}。
     */
    private void clearTableParallelByPk(WorkConns conns, String catalog, String tableName, String tableTag,
                                        DatabaseType dbType, int insertBatchSize, List<String> primaryKeys,
                                        int parallel, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        String lockKey = targetClearLockKey(catalog, tableName);
        ReentrantLock clearLock = targetClearLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!clearLock.tryLock()) {
            log.warn("{} 等待同目标表清表锁释放: {}.{}", tableTag, catalog, tableName);
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "warn", tableTag + " 等待同目标表清表完成（并行分段 DELETE）");
            }
            clearLock.lock();
        }
        try {
            doClearTableParallelByPk(conns, catalog, tableName, tableTag, dbType, insertBatchSize,
                    primaryKeys, parallel, task, lastSaveTime);
        } finally {
            clearLock.unlock();
            if (!clearLock.hasQueuedThreads() && !clearLock.isLocked()) {
                targetClearLocks.remove(lockKey, clearLock);
            }
        }
    }

    private void doClearTableParallelByPk(WorkConns conns, String catalog, String tableName, String tableTag,
                                          DatabaseType dbType, int insertBatchSize, List<String> primaryKeys,
                                          int parallel, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        if (!supportsMysqlFamily(dbType)
                || primaryKeys == null || primaryKeys.size() != 1
                || parallel <= 1) {
            log.warn("{} 并行分段 DELETE 条件不满足（需 MySQL 系单列主键且并行>1），回退单线程 DELETE",
                    tableTag);
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "warn", tableTag + " 并行分段条件不足，回退单线程分批 DELETE");
            }
            doClearTable(conns, catalog, tableName, tableTag, dbType, insertBatchSize, task, lastSaveTime);
            return;
        }
        String qualifiedName = buildQualifiedName(catalog, tableName, dbType);
        String pk = primaryKeys.get(0);
        String pkQuoted = "`" + pk.replace("`", "") + "`";
        long[] minMax = queryPkMinMax(conns.tgt, qualifiedName, pkQuoted, tableTag);
        if (minMax == null) {
            log.warn("{} 无法读取主键 {} MIN/MAX（空表或非数值），回退单线程 DELETE", tableTag, pk);
            if (task != null && task.getId() != null) {
                uiLog(task.getId(), "warn", tableTag + " 主键 MIN/MAX 不可用，回退单线程分批 DELETE");
            }
            doClearTable(conns, catalog, tableName, tableTag, dbType, insertBatchSize, task, lastSaveTime);
            return;
        }
        List<long[]> ranges = DataxClearMode.splitPkRanges(minMax[0], minMax[1], parallel);
        if (ranges.isEmpty()) {
            doClearTable(conns, catalog, tableName, tableTag, dbType, insertBatchSize, task, lastSaveTime);
            return;
        }
        int deleteBatch = Math.max(FALLBACK_DELETE_BATCH_SIZE, resolveDeleteBatchSize(insertBatchSize));
        // 主连接归还，避免占着池槽；各 worker 各自借
        conns.releaseTarget();
        checkCancel(task.getId(), "clear", true);
        long deleteStart = System.currentTimeMillis();
        AtomicLong totalDeleted = new AtomicLong(0);
        AtomicLong lastHbAt = new AtomicLong(deleteStart);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " 并行分段清表: pk=" + pk
                    + " 区间=[" + minMax[0] + "," + minMax[1] + "] 段数=" + ranges.size()
                    + " 每批 LIMIT " + deleteBatch);
        }
        updateTaskDetail(task, lastSaveTime, tableTag + " · 并行清表 x" + ranges.size());

        ExecutorService pool = Executors.newFixedThreadPool(ranges.size());
        List<Future<?>> futures = new ArrayList<>(ranges.size());
        AtomicReference<Exception> firstError = new AtomicReference<>();
        for (int i = 0; i < ranges.size(); i++) {
            final int seg = i;
            final long lo = ranges.get(i)[0];
            final long hi = ranges.get(i)[1];
            futures.add(pool.submit(() -> {
                try {
                    long deleted = deletePkRangeLoop(conns, qualifiedName, pkQuoted, lo, hi, deleteBatch,
                            tableTag, task, totalDeleted, lastHbAt, lastSaveTime, ranges.size());
                    log.info("{} 分段#{} 完成 [{}, {}) 删 {} 行", tableTag, seg, lo, hi, deleted);
                } catch (Exception e) {
                    firstError.compareAndSet(null, e);
                }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    firstError.compareAndSet(null,
                            ee.getCause() instanceof Exception ? (Exception) ee.getCause() : ee);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    firstError.compareAndSet(null, ie);
                }
            }
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) {
                pool.shutdownNow();
                throw new SQLException("并行清表超时");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new SyncAbortException(OUT_CLEAR_FAILED, "clear", true, "清表已取消");
        }
        Exception err = firstError.get();
        if (err != null) {
            if (err instanceof SyncAbortException) {
                throw (SyncAbortException) err;
            }
            if (err instanceof SQLException) {
                throw (SQLException) err;
            }
            throw new SQLException("并行清表失败: " + err.getMessage(), err);
        }
        conns.borrowTarget(true);
        long elapsed = Math.max(1L, (System.currentTimeMillis() - deleteStart) / 1000L);
        log.info("{} 并行清表完成, 共删 {} 行, 段数 {}, 耗时 {}s",
                tableTag, totalDeleted.get(), ranges.size(), elapsed);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " 并行清表完成, 共删 " + totalDeleted.get()
                    + " 行, 段数 " + ranges.size() + ", 耗时 " + elapsed + "s");
        }
    }

    /** @return [min,max] 或 null（空表/非数值） */
    private long[] queryPkMinMax(Connection conn, String qualified, String pkQuoted, String tableTag)
            throws SQLException {
        String sql = "SELECT MIN(" + pkQuoted + "), MAX(" + pkQuoted + ") FROM " + qualified;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            Object a = rs.getObject(1);
            Object b = rs.getObject(2);
            if (a == null || b == null) {
                return null;
            }
            try {
                long min = ((Number) a).longValue();
                long max = ((Number) b).longValue();
                return new long[]{min, max};
            } catch (ClassCastException ex) {
                log.warn("{} 主键非数值类型: {}", tableTag, a.getClass().getSimpleName());
                return null;
            }
        }
    }

    private long deletePkRangeLoop(WorkConns conns, String qualified, String pkQuoted,
                                   long lo, long hiExclusive, int deleteBatch, String tableTag,
                                   SyncTask task, AtomicLong totalDeleted, AtomicLong lastHbAt,
                                   AtomicInteger lastSaveTime, int segments)
            throws SQLException, SyncAbortException {
        String sql = "DELETE FROM " + qualified + " WHERE " + pkQuoted + " >= ? AND "
                + pkQuoted + " < ? LIMIT " + deleteBatch;
        Connection conn = null;
        long localDeleted = 0;
        try {
            conn = conns.borrowExtraTarget(true);
            applyClearSessionHints(conn, tableTag);
            conn.setAutoCommit(true);
            int lockWaitRetries = 0;
            final int maxLockWaitRetries = 8;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                while (true) {
                    checkCancel(task.getId(), "clear", true);
                    touchWatchdog();
                    ps.setLong(1, lo);
                    ps.setLong(2, hiExclusive);
                    int affected;
                    try {
                        affected = ps.executeUpdate();
                        lockWaitRetries = 0;
                    } catch (SQLException e) {
                        if (isLockWaitTimeout(e) && lockWaitRetries < maxLockWaitRetries) {
                            lockWaitRetries++;
                            try {
                                Thread.sleep(Math.min(30_000L, 1000L * lockWaitRetries * lockWaitRetries));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new SyncAbortException(OUT_CLEAR_FAILED, "clear", true, "清表已取消");
                            }
                            continue;
                        }
                        throw e;
                    }
                    if (affected <= 0) {
                        break;
                    }
                    localDeleted += affected;
                    long all = totalDeleted.addAndGet(affected);
                    long now = System.currentTimeMillis();
                    long prevHb = lastHbAt.get();
                    if (now - prevHb >= 5_000L && lastHbAt.compareAndSet(prevHb, now)) {
                        if (task != null && task.getId() != null) {
                            uiLog(task.getId(), "info", tableTag + " 【心跳】并行清表已删 " + all
                                    + " 行（x" + segments + "）");
                            updateTaskDetail(task, lastSaveTime,
                                    tableTag + " · 并行清表已删 " + all + " 行");
                        }
                    }
                }
            }
        } finally {
            SyncJdbcPools.release(conn);
        }
        return localDeleted;
    }

    /** 目标表清表互斥键：catalog + table（忽略源库差异） */
    static String targetClearLockKey(String catalog, String tableName) {
        String c = catalog == null ? "" : catalog.trim();
        String t = tableName == null ? "" : tableName.trim();
        return c + '\0' + t;
    }

    private void doClearTable(WorkConns conns, String catalog, String tableName, String tableTag,
                              DatabaseType dbType, int insertBatchSize, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        String qualifiedName = buildQualifiedName(catalog, tableName, dbType);
        int deleteBatch = resolveDeleteBatchSize(insertBatchSize);
        checkCancel(task.getId(), "clear", true);
        touchWatchdog();
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " 【心跳】仍在运行 · 开始清表");
        }

        if (supportsMysqlFamily(dbType)) {
            batchDeleteTable(conns, qualifiedName, tableTag, deleteBatch, task, lastSaveTime);
            return;
        }

        updateTaskDetail(task, lastSaveTime, tableTag + " · 清表中");
        String deleteAllSql = "DELETE FROM " + qualifiedName;
        log.info("{} 执行 [目标库 DELETE]（非 MySQL 系）: {}", tableTag, deleteAllSql);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " [目标 DELETE] " + deleteAllSql);
        }
        long deleteStart = System.currentTimeMillis();
        try (Statement stmt = conns.tgt.createStatement()) {
            int deleted = stmt.executeUpdate(deleteAllSql);
            log.info("{} 清表完成, 删除 {} 行, 耗时 {}s", tableTag, deleted,
                    Math.max(1, (System.currentTimeMillis() - deleteStart) / 1000));
        }
    }

    private void batchDeleteTable(WorkConns conns, String qualifiedName, String tableTag,
                                  int batchSize, SyncTask task, AtomicInteger lastSaveTime)
            throws SQLException, SyncAbortException {
        // 千万级清表必须保持大批次；锁冲突时缩小 LIMIT 只会拖死进度，且通常仍抢不到锁
        int deleteBatch = Math.max(FALLBACK_DELETE_BATCH_SIZE, batchSize);
        String deleteSql = "DELETE FROM " + qualifiedName + " LIMIT " + deleteBatch;
        long totalDeleted = 0;
        int rounds = 0;
        long deleteStart = System.currentTimeMillis();
        ProgressLogClock progressClock = new ProgressLogClock(deleteStart);
        long lastRecycleAt = deleteStart;
        boolean reconnectRetried = false;
        int lockWaitRetries = 0;
        final int maxLockWaitRetries = 8;
        log.info("{} 分批清表(仅DML·目标库), 每批 {} 行", tableTag, deleteBatch);
        log.info("{} [目标库 DELETE] {}", tableTag, deleteSql);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " [目标 DELETE] " + deleteSql);
        }
        updateTaskDetail(task, lastSaveTime, tableTag + " · 分批清表");

        Connection conn = conns.tgt;
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        applyClearSessionHints(conn, tableTag);
        Statement stmt = conn.createStatement();
        try {
            while (true) {
                checkCancel(task.getId(), "clear", true);
                touchWatchdog();
                int affected;
                try {
                    affected = stmt.executeUpdate(deleteSql);
                    lockWaitRetries = 0;
                } catch (SQLException e) {
                    if (isLockWaitTimeout(e) && lockWaitRetries < maxLockWaitRetries) {
                        lockWaitRetries++;
                        log.warn("{} 清表锁等待超时(1205)，保持每批 {} 行，第 {}/{} 次退避后重试: {}",
                                tableTag, deleteBatch, lockWaitRetries, maxLockWaitRetries,
                                summarizeJdbcError(e));
                        if (task != null && task.getId() != null) {
                            uiLog(task.getId(), "warn", tableTag
                                    + " 清表锁等待超时：目标表被其他事务占用。保持每批 " + deleteBatch
                                    + " 行，等待后重试 (" + lockWaitRetries + "/" + maxLockWaitRetries
                                    + ")。请在目标库查 PROCESSLIST/长事务");
                        }
                        try {
                            Thread.sleep(Math.min(15_000L, 2_000L * lockWaitRetries));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new SyncAbortException(OUT_CLEAR_FAILED, "clear", true, "清表已取消");
                        }
                        touchWatchdog();
                        try {
                            stmt.close();
                        } catch (SQLException ignored) {
                        }
                        try {
                            conns.recycleTargetOnly(tableTag);
                        } catch (SQLException recycleEx) {
                            conns.discardTarget();
                            conns.borrowTarget(true);
                        }
                        conn = conns.tgt;
                        prevAuto = conn.getAutoCommit();
                        conn.setAutoCommit(true);
                        applyClearSessionHints(conn, tableTag);
                        stmt = conn.createStatement();
                        lastRecycleAt = System.currentTimeMillis();
                        continue;
                    }
                    if (!reconnectRetried && isRetriableJdbcFailure(e) && !isLockWaitTimeout(e)) {
                        reconnectRetried = true;
                        log.warn("{} 清表连接/协议异常，丢弃并重借目标连接后继续: {}",
                                tableTag, summarizeJdbcError(e));
                        try {
                            stmt.close();
                        } catch (SQLException ignored) {
                        }
                        conns.discardTarget();
                        conns.borrowTarget(true);
                        conn = conns.tgt;
                        prevAuto = conn.getAutoCommit();
                        conn.setAutoCommit(true);
                        applyClearSessionHints(conn, tableTag);
                        stmt = conn.createStatement();
                        lastRecycleAt = System.currentTimeMillis();
                        affected = stmt.executeUpdate(deleteSql);
                    } else {
                        throw enrichClearLockHint(e);
                    }
                }
                totalDeleted += affected;
                rounds++;
                long now = System.currentTimeMillis();
                if (rounds == 1 && affected == 0) {
                    log.info("{} 目标为空，跳过清表", tableTag);
                    return;
                }
                if (now - progressClock.lastAtMs >= PROGRESS_LOG_INTERVAL_SEC * 1000L || affected < deleteBatch) {
                    long elapsedSec = Math.max(1, (now - deleteStart) / 1000);
                    long windowSec = Math.max(1, (now - progressClock.lastAtMs) / 1000);
                    long deltaRows = Math.max(0, totalDeleted - progressClock.lastRows);
                    String speed = ProgressTextHelper.formatRowsPerSec(
                            totalDeleted, elapsedSec, deltaRows, windowSec);
                    String heartbeat = "【心跳】仍在运行 · 清表已删除 " + totalDeleted + " 行, 第 " + rounds
                            + " 批(每批 " + deleteBatch + "), 用时 " + elapsedSec + "s, " + speed;
                    log.info("{} {}", tableTag, heartbeat);
                    updateTaskDetail(task, lastSaveTime, tableTag + " · 清表 " + totalDeleted + " 行");
                    uiLog(task.getId(), "info", tableTag + " " + heartbeat);
                    progressClock.lastAtMs = now;
                    progressClock.lastRows = totalDeleted;
                }
                if (affected < deleteBatch) {
                    break;
                }
                if (now - lastRecycleAt >= LONG_PHASE_RECYCLE_MS) {
                    try {
                        stmt.close();
                    } catch (SQLException ignored) {
                    }
                    conns.recycleTargetOnly(tableTag);
                    conn = conns.tgt;
                    prevAuto = conn.getAutoCommit();
                    conn.setAutoCommit(true);
                    applyClearSessionHints(conn, tableTag);
                    stmt = conn.createStatement();
                    lastRecycleAt = now;
                }
            }
        } finally {
            try {
                stmt.close();
            } catch (SQLException ignored) {
            }
            try {
                if (conns.tgt != null) {
                    conns.tgt.setAutoCommit(prevAuto);
                }
            } catch (SQLException ignored) {
            }
        }
        log.info("{} 清表完成, 共删除 {} 行, 批次数 {}, 耗时 {}s",
                tableTag, totalDeleted, rounds,
                Math.max(1, (System.currentTimeMillis() - deleteStart) / 1000));
        uiLog(task.getId(), "info", tableTag + " 清表完成, 共删除 " + totalDeleted + " 行, 批次数 " + rounds);
    }

    /** 清表会话：拉长锁等待（默认常仅数十秒）；无权限则忽略 */
    private void applyClearSessionHints(Connection conn, String tableTag) {
        if (conn == null) {
            return;
        }
        try (Statement s = conn.createStatement()) {
            s.execute("SET SESSION innodb_lock_wait_timeout = 300");
        } catch (SQLException e) {
            log.debug("{} 无法设置 innodb_lock_wait_timeout: {}", tableTag, e.getMessage());
        }
    }

    /** 锁等待超时：MySQL/GoldenDB 1205，SQLState 常为 40001 / HY000 */
    static boolean isLockWaitTimeout(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                if (se.getErrorCode() == 1205) {
                    return true;
                }
                String msg = se.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("lock wait timeout")
                            || lower.contains("lock_wait_timeout")) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static SQLException enrichClearLockHint(SQLException e) {
        if (!isLockWaitTimeout(e)) {
            return e;
        }
        String hint = "清表 DELETE 锁等待超时(1205)：目标表被其他会话锁住（业务写入/长事务），与每批行数无关。"
                + "请在目标库查 PROCESSLIST 结束阻塞事务后重试；低峰同步；或 DataX 清表改为「不清空」后手工清表。"
                + " 原始: " + summarizeJdbcErrorStatic(e);
        SQLException wrapped = new SQLException(hint, e.getSQLState(), e.getErrorCode());
        wrapped.initCause(e);
        return wrapped;
    }

    private static String summarizeJdbcErrorStatic(Throwable e) {
        if (e == null) {
            return "";
        }
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    private static boolean isConnectivityFailure(Throwable e) {
        return isRetriableJdbcFailure(e);
    }

    /**
     * 连接断开或 MySQL 驱动协议损坏（GoldenDB 高并发常见 ArrayIndexOutOfBoundsException → SQLException:null）。
     */
    static boolean isRetriableJdbcFailure(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof ArrayIndexOutOfBoundsException
                    || cur instanceof IndexOutOfBoundsException
                    || cur instanceof NullPointerException) {
                return true;
            }
            String cn = cur.getClass().getName();
            if (cn.contains("CJCommunicationsException")
                    || cn.contains("PacketTooBigException")
                    || cn.contains("CJPacketTooBigException")) {
                return true;
            }
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                String state = se.getSQLState();
                String msg = se.getMessage();
                boolean emptyMsg = msg == null || msg.trim().isEmpty();
                if ("08S01".equals(state) || "40001".equals(state)) {
                    return true;
                }
                // 空消息：Connector/J 常把 AIOOBE 包成 SQLException:null / SQLState=S1000
                if (emptyMsg) {
                    return true;
                }
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("wait_timeout")
                            || lower.contains("communications link failure")
                            || lower.contains("connection reset")
                            || lower.contains("broken pipe")
                            || lower.contains("no operations allowed after connection closed")
                            || lower.contains("connection is closed")
                            || lower.contains("packet")
                            || lower.contains("stream closed")
                            || lower.contains("can not read response")
                            || lower.contains("arrayindexoutofbounds")
                            || lower.contains("error retrieving record")
                            || lower.contains("unexpected exception")) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
            depth++;
        }
        if (e instanceof SQLException) {
            for (Throwable s : e.getSuppressed()) {
                if (s != e && isRetriableJdbcFailureShallow(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 仅扫 cause 链，避免 suppressed 递归过深 */
    private static boolean isRetriableJdbcFailureShallow(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur instanceof ArrayIndexOutOfBoundsException
                    || cur instanceof IndexOutOfBoundsException
                    || cur instanceof NullPointerException) {
                return true;
            }
            if (cur instanceof SQLException) {
                String msg = cur.getMessage();
                if (msg == null || msg.trim().isEmpty()) {
                    return true;
                }
                String lower = msg.toLowerCase();
                if (lower.contains("error retrieving record") || lower.contains("unexpected exception")
                        || lower.contains("communications link failure")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static String summarizeJdbcError(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 6) {
            String m = cur.getMessage();
            if (m == null || m.trim().isEmpty()) {
                m = cur.getClass().getSimpleName();
            }
            if (sb.length() > 0) {
                sb.append(" ← ");
            }
            sb.append(m.trim());
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                if (se.getSQLState() != null) {
                    sb.append(" [SQLState=").append(se.getSQLState()).append("]");
                }
            }
            cur = cur.getCause();
            depth++;
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private void maybeLogCopyProgress(String tableTag, long totalRows, int committedBatches,
                                      long copyStart, ProgressLogClock clock,
                                      SyncTask task, AtomicInteger lastSaveTime) {
        long now = System.currentTimeMillis();
        if (now - clock.lastAtMs < PROGRESS_LOG_INTERVAL_SEC * 1000L) {
            return;
        }
        maybeMarkLargeTable(tableTag, totalRows, task);
        long elapsedSec = Math.max(1, (now - copyStart) / 1000);
        long windowSec = Math.max(1, (now - clock.lastAtMs) / 1000);
        long deltaRows = Math.max(0, totalRows - clock.lastRows);
        String speed = ProgressTextHelper.formatRowsPerSec(totalRows, elapsedSec, deltaRows, windowSec);
        String heartbeat = "【心跳】仍在运行 · 已写入 " + totalRows + " 行 (" + committedBatches + " 批), 用时 "
                + elapsedSec + "s, " + speed;
        log.info("{} {}", tableTag, heartbeat);
        updateTaskDetail(task, lastSaveTime, tableTag + " · 写入 " + totalRows + " 行");
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", tableTag + " " + heartbeat);
        }
        clock.lastAtMs = now;
        clock.lastRows = totalRows;
    }

    /** 写入/清表心跳用的均速+近段速度游标 */
    private static final class ProgressLogClock {
        long lastAtMs;
        long lastRows;

        ProgressLogClock(long startMs) {
            this.lastAtMs = startMs;
            this.lastRows = 0;
        }
    }

    /**
     * 估算或已写入行数达到阈值时，仅对该表放宽看门狗，并打一条说明日志。
     */
    private void maybeMarkLargeTable(String tableTag, long rowsHint, SyncTask task) {
        maybeMarkLargeTable(tableTag, rowsHint, task, SyncIdleWatchdog.LARGE_TABLE_ROW_THRESHOLD);
    }

    private void maybeMarkLargeTable(String tableTag, long rowsHint, SyncTask task, long threshold) {
        if (rowsHint < 0 || rowsHint < threshold) {
            return;
        }
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        if (wd == null) {
            return;
        }
        if (!wd.markLargeTable(tableTag)) {
            return;
        }
        String msg = tableTag + " 判定超大表（行数≈" + rowsHint + "≥" + threshold
                + "），看门狗放宽：单表 "
                + (SyncIdleWatchdog.LARGE_TABLE_IDLE_MS / 1000) + "s / 整任务 "
                + (SyncIdleWatchdog.LARGE_TASK_IDLE_MS / 1000) + "s（仍需【心跳】证明在跑）";
        log.info(msg);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "info", msg);
            if (task.isFullDmlMode()) {
                uiLog(task.getId(), "info", tableTag
                        + " 提示：DML 全量写入时二级索引随表变大，近段速度变慢属常见现象；"
                        + "看心跳「近段」比「均速」更准。大表更建议用 DDL 全量（TRUNCATE/换表）");
            }
        }
    }

    /**
     * 快速估算源表行数（information_schema / 失败则 -1），仅用于是否放宽看门狗，不参与同步正确性。
     */
    private long estimateTableRows(Connection conn, String catalog, String tableName, DatabaseType dbType) {
        if (conn == null || tableName == null || tableName.isEmpty()) {
            return -1L;
        }
        if (dbType != DatabaseType.MYSQL && dbType != DatabaseType.GOLDENDB
                && dbType != DatabaseType.H2) {
            return -1L;
        }
        String schema = catalog;
        if (schema == null || schema.isEmpty()) {
            try {
                schema = conn.getCatalog();
            } catch (SQLException ignored) {
                schema = null;
            }
        }
        if (schema == null || schema.isEmpty()) {
            return -1L;
        }
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long n = rs.getLong(1);
                    return rs.wasNull() ? -1L : n;
                }
            }
        } catch (SQLException e) {
            log.debug("{} 估算行数失败(可忽略): {}", tableName, e.getMessage());
        }
        return -1L;
    }

    private long countTableRows(Connection conn, String qualifiedName, String tableTag, String role)
            throws SQLException {
        if (conn == null || qualifiedName == null || qualifiedName.isEmpty()) {
            throw new SQLException("COUNT 失败：连接或表名为空");
        }
        String sql = "SELECT COUNT(*) FROM " + qualifiedName;
        long t0 = System.currentTimeMillis();
        log.info("{} 执行 [{} COUNT] {}", tableTag, role, sql);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("COUNT 无结果: " + qualifiedName);
            }
            long n = rs.getLong(1);
            log.info("{} {} COUNT(*)={}，耗时 {}ms", tableTag, role, n,
                    System.currentTimeMillis() - t0);
            return n;
        }
    }

    /** COUNT 期间后台心跳，避免大表 COUNT 被看门狗误杀 */
    private long countTableRowsWithHeartbeat(Connection conn, String qualifiedName,
                                             String tableTag, Long taskId, String role)
            throws SQLException {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread hb = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (!done.get()) {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (done.get()) {
                    return;
                }
                touchWatchdog();
                long sec = (System.currentTimeMillis() - t0) / 1000;
                if (taskId != null) {
                    uiLog(taskId, "info", tableTag + " 【心跳】仍在运行 · COUNT " + role + "中（已 " + sec + "s）");
                }
            }
        }, "count-hb-" + role + "-" + tableTag);
        hb.setDaemon(true);
        hb.start();
        try {
            return countTableRows(conn, qualifiedName, tableTag, role);
        } finally {
            done.set(true);
            hb.interrupt();
            try {
                hb.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void updateTaskDetail(SyncTask task, AtomicInteger lastSaveTime, String detail) {
        if (task == null) {
            return;
        }
        touchWatchdog();
        String ui = ProgressTextHelper.build(TL_RUNNING.get(), detail);
        synchronized (task) {
            task.setCurrentTable(ui);
            int nowSec = (int) (System.currentTimeMillis() / 1000);
            if (nowSec - lastSaveTime.get() >= 2) {
                lastSaveTime.set(nowSec);
                saveTaskProgressQuietly(task);
            }
        }
    }

    /** 进度字段写入失败不应中断真实同步（旧库列过短时曾导致全表误报失败） */
    private void saveTaskProgressQuietly(SyncTask task) {
        try {
            syncTaskRepository.save(task);
        } catch (Exception e) {
            log.warn("保存同步进度失败（不影响表同步）: {}", e.getMessage());
        }
    }

    /** 进度页展示正在并行的多张表，避免误以为顺序执行 */
    private void refreshRunningDetail(SyncTask task, Set<String> running, AtomicInteger lastSaveTime) {
        if (running == null || running.isEmpty()) {
            return;
        }
        updateTaskDetail(task, lastSaveTime, "并行×" + running.size());
    }

    /** 日志/进度标签：#序号/总数 源库.表 → 目标库.表 */
    private String formatTableTag(int index, int total, String srcCat, String tgtCat, String tableName) {
        return "#" + index + "/" + total + " "
                + formatCatalogTable(srcCat, tableName) + " → " + formatCatalogTable(tgtCat, tableName);
    }

    private String formatCatalogTable(String catalog, String tableName) {
        if (catalog != null && !catalog.isEmpty()) {
            return catalog + "." + tableName;
        }
        return tableName != null ? tableName : "?";
    }

    private String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim().split(",")[0].trim();
        }
        return a;
    }

    private String formatTablePair(String srcCat, String tgtCat, String tableName) {
        return formatCatalogTable(srcCat, tableName) + " → " + formatCatalogTable(tgtCat, tableName);
    }

    /**
     * 获取表的列名列表（兼容 MySQL catalog / H2·PG schema）
     */
    private List<String> getColumnNames(Connection conn, String catalog, String tableName, DatabaseType dbType) throws SQLException {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try {
            // 1) MySQL 风格：catalog=库名
            if (catalog != null && !catalog.isEmpty()) {
                try (ResultSet rs = meta.getColumns(catalog, null, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            // 2) H2/PG 风格：schema=库名或 PUBLIC
            if (columns.isEmpty() && catalog != null && !catalog.isEmpty()) {
                try (ResultSet rs = meta.getColumns(null, catalog, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            // 3) 不限定库
            if (columns.isEmpty()) {
                try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("元数据读取列失败: {} — {}", tableName, e.getMessage());
        }
        // 4) SELECT 探测（元数据为空时兜底）
        if (columns.isEmpty()) {
            String sql = buildQualifiedName(catalog, tableName, dbType);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM " + sql + " WHERE 1=0")) {
                ResultSetMetaData rsMeta = rs.getMetaData();
                for (int i = 1; i <= rsMeta.getColumnCount(); i++) {
                    columns.add(rsMeta.getColumnName(i));
                }
            } catch (SQLException e2) {
                // 再试裸表名
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1=0")) {
                    ResultSetMetaData rsMeta = rs.getMetaData();
                    for (int i = 1; i <= rsMeta.getColumnCount(); i++) {
                        columns.add(rsMeta.getColumnName(i));
                    }
                }
            }
        }
        return columns;
    }

    /** 读取主键列（兼容 catalog / schema，按 KEY_SEQ 排序） */
    private List<String> getPrimaryKeys(Connection conn, String catalog, String tableName, DatabaseType dbType)
            throws SQLException {
        Map<Integer, String> ordered = new TreeMap<>();
        DatabaseMetaData meta = conn.getMetaData();
        try {
            collectPrimaryKeys(meta, catalog, null, tableName, ordered);
            if (ordered.isEmpty() && catalog != null && !catalog.isEmpty()) {
                collectPrimaryKeys(meta, null, catalog, tableName, ordered);
            }
            if (ordered.isEmpty()) {
                collectPrimaryKeys(meta, null, null, tableName, ordered);
            }
        } catch (SQLException e) {
            log.warn("元数据读取主键失败: {} — {}", tableName, e.getMessage());
        }
        return new ArrayList<>(ordered.values());
    }

    private static void collectPrimaryKeys(DatabaseMetaData meta, String catalog, String schema,
                                           String tableName, Map<Integer, String> ordered) throws SQLException {
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                int seq = rs.getInt("KEY_SEQ");
                if (col != null && !col.isEmpty()) {
                    ordered.put(seq > 0 ? seq : ordered.size() + 1, col);
                }
            }
        }
    }

    // ============ 辅助方法 ============

    private String quoteColumnList(List<String> columns, DatabaseType dbType) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(DatabaseDialect.escapeIdentifier(columns.get(i), dbType));
        }
        return sb.toString();
    }

    private String buildQualifiedName(String catalog, String pureTableName, DatabaseType dbType) {
        if (catalog == null || catalog.isEmpty()) return pureTableName;
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            return "`" + catalog + "`.`" + pureTableName + "`";
        }
        if (dbType == DatabaseType.POSTGRESQL || dbType == DatabaseType.ORACLE || dbType == DatabaseType.H2) {
            return catalog + "." + pureTableName;
        }
        if (dbType == DatabaseType.SQLSERVER) {
            return catalog + ".dbo." + pureTableName;
        }
        return catalog + "." + pureTableName;
    }

    private int resolveDeleteBatchSize(int insertBatchSize) {
        int configured = FALLBACK_DELETE_BATCH_SIZE;
        try {
            configured = appSettingsService.getSettings().getSyncDeleteBatchSize();
        } catch (Exception ignored) {
        }
        return Math.max(insertBatchSize, configured);
    }

    private void onDataxLogLine(Long taskId, SyncTask task, AtomicInteger lastSaveTime,
                                SyncIdleWatchdog dataxWd, String dataxTag,
                                DataxLogMonitor.Snapshot snap, AtomicLong lastProgressLogMs,
                                String line, int tableTotal, long dataxPhaseStartMs,
                                boolean dataxDeleteClear) {
        if (dataxWd != null) {
            dataxWd.touch(dataxTag);
        }
        // 本工具写入的排队/作业说明（非 DataX 原生日志）
        if (line != null && (line.contains("【心跳】") || line.contains("DataX 作业")
                || line.contains("槽位") || line.contains("排队等待"))) {
            progressLogBuffer.append(taskId, "info",
                    line.startsWith(dataxTag) ? line : (dataxTag + " " + line));
            return;
        }
        DataxLogMonitor.Kind kind = DataxLogMonitor.consume(line, snap);
        long now = System.currentTimeMillis();
        if (kind == DataxLogMonitor.Kind.PROGRESS || kind == DataxLogMonitor.Kind.SUMMARY) {
            int within = calcDataxWithinTablePct(snap, dataxPhaseStartMs, dataxDeleteClear);
            boolean allowDec = snap.sawProgress && within <= 55;
            publishLiveTableProgress(task, dataxTag, within, tableTotal, lastSaveTime, allowDec);
        }
        switch (kind) {
            case PROGRESS:
                updateTaskDetail(task, lastSaveTime, dataxTag + " · DataX " + snap.shortStatus());
                // DataX 进度约每秒一条；UI 约 1s 刷一次，保留 MB/s 与行/秒
                if (now - lastProgressLogMs.get() >= 1_000L) {
                    lastProgressLogMs.set(now);
                    progressLogBuffer.append(taskId, "info",
                            dataxTag + " 【DataX进度】" + snap.progressDetail());
                }
                break;
            case SUMMARY:
                updateTaskDetail(task, lastSaveTime, dataxTag + " · DataX " + snap.shortStatus());
                progressLogBuffer.append(taskId, "success",
                        dataxTag + " 【DataX摘要】" + line.trim());
                break;
            case ERROR:
                progressLogBuffer.append(taskId, "error",
                        dataxTag + " 【DataX】" + trimDataxLine(line));
                break;
            case INFO:
                progressLogBuffer.append(taskId, "info",
                        dataxTag + " 【DataX】" + trimDataxLine(line));
                break;
            case IGNORE:
            default:
                break;
        }
    }

    /**
     * 单表 DataX 进度 0–100。
     * <p>
     * 清表阶段：本表%直接等于清表完成度（不再按源/目标行数加权压到 ~40%），
     * 避免「清表 95% 但本表卡在 41%」；
     * 写入阶段：从 50% 起按源 COUNT 推进到 99%。
     */
    static int calcDataxWithinTablePct(DataxLogMonitor.Snapshot snap, long phaseStartMs,
                                       boolean dataxDeleteClear) {
        if (snap == null) {
            return 0;
        }
        long tgt = Math.max(0L, snap.clearTargetRows);
        long src = Math.max(0L, snap.sourceTotalRows);

        if (dataxDeleteClear && !snap.sawProgress) {
            if (tgt <= 0) {
                return 5;
            }
            double clearFrac = estimateClearFraction(snap, tgt, phaseStartMs);
            return (int) Math.min(99, Math.round(clearFrac * 100.0d));
        }

        double writeFrac = 0.0;
        if (src > 0) {
            long done = snap.bestRowCount() >= 0 ? snap.bestRowCount() : snap.totalRecords;
            if (snap.sawProgress || snap.sawSummary) {
                writeFrac = Math.min(1.0d, Math.max(0L, done) / (double) src);
            }
        } else if (snap.sawProgress) {
            writeFrac = Math.min(1.0d, Math.max(0d, snap.percentage) / 100.0d);
        }
        if (dataxDeleteClear) {
            return (int) Math.min(99, Math.round(50.0d + writeFrac * 49.0d));
        }
        return (int) Math.min(99, Math.round(writeFrac * 99.0d));
    }

    /**
     * 清表完成比例 0–0.99。优先剩余行；否则按时间估算，超过预估时长后指数爬升，避免一直 95%。
     */
    static double estimateClearFraction(DataxLogMonitor.Snapshot snap, long tgt, long phaseStartMs) {
        if (tgt <= 0) {
            return 1.0;
        }
        if (snap != null && snap.clearRemainingRows >= 0) {
            long rem = Math.min(tgt, snap.clearRemainingRows);
            long cleared = Math.max(0L, tgt - rem);
            double frac = cleared / (double) tgt;
            // 剩余很少但仍未进入写入：顶到 99%，表示清表基本结束
            if (rem <= Math.max(50_000L, tgt / 1000L)) {
                return 0.99d;
            }
            return Math.min(0.99d, Math.max(0d, frac));
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - phaseStartMs);
        double expectedMs = (tgt / (double) ASSUMED_DATAX_DELETE_ROWS_PER_SEC) * 1000.0d;
        if (expectedMs < 1_000d) {
            expectedMs = 1_000d;
        }
        if (elapsedMs <= expectedMs) {
            return Math.min(0.90d, elapsedMs / expectedMs);
        }
        // 已超预估：90% → 98% 缓慢爬升（约再 20 分钟贴近 98%）
        double extra = (elapsedMs - expectedMs) / 1000.0d;
        return Math.min(0.98d, 0.90d + 0.08d * (1.0d - Math.exp(-extra / 600.0d)));
    }

    static String formatDataxProgressHint(DataxLogMonitor.Snapshot snap, int withinPct,
                                          boolean dataxDeleteClear, long tgt, long src,
                                          long phaseStartMs) {
        if (snap != null && snap.sawProgress) {
            return snap.shortStatus() + " · 本表约 " + withinPct + "%";
        }
        if (dataxDeleteClear && tgt > 0) {
            long elapsedSec = Math.max(0L, (System.currentTimeMillis() - phaseStartMs) / 1000L);
            if (snap != null && snap.clearRemainingRows >= 0) {
                long rem = snap.clearRemainingRows;
                long cleared = Math.max(0L, tgt - Math.min(tgt, rem));
                int clearPct = (int) Math.min(99, cleared * 100 / tgt);
                String rate = formatClearRateText(cleared, elapsedSec, snap.clearRecentRowsPerSec);
                return "清表 " + clearPct + "%（已删约 " + cleared + "/" + tgt
                        + "，剩余 " + rem + "，" + rate
                        + "，已运行 " + elapsedSec + "s）· 本表 " + withinPct
                        + "%（=清表进度）· 其后写入源 " + src + " 行"
                        + "（写入后可见 DataX MB/s）";
            }
            double frac = estimateClearFraction(snap, tgt, phaseStartMs);
            int clearPct = (int) Math.round(frac * 100);
            long estDeleted = Math.min(tgt, Math.round(frac * tgt));
            String pace = frac >= 0.90d
                    ? "预估偏快，仍在 preSql 清表"
                    : "按约 " + ASSUMED_DATAX_DELETE_ROWS_PER_SEC + " 行/秒估";
            String rate = formatClearRateText(estDeleted, elapsedSec,
                    snap != null ? snap.clearRecentRowsPerSec : -1L);
            return "清表 " + clearPct + "%（估已删 " + estDeleted + "/" + tgt
                    + "，" + pace + "，" + rate
                    + "，已 " + elapsedSec + "s）· 本表 " + withinPct
                    + "%（=清表进度）· 其后写入源 " + src + " 行"
                    + "（写入后可见 DataX MB/s）";
        }
        return "启动中 · 本表约 " + withinPct + "% · 源 COUNT=" + src;
    }

    /** 清表吞吐：均速 + 近段（有轮询增量时） */
    static String formatClearRateText(long cleared, long elapsedSec, long recentRowsPerSec) {
        long avg = elapsedSec > 0 ? cleared / elapsedSec : 0L;
        if (recentRowsPerSec >= 0) {
            return "均速 " + avg + " 行/秒, 近段 " + recentRowsPerSec + " 行/秒";
        }
        return "均速 " + avg + " 行/秒";
    }

    private void publishLiveTableProgress(SyncTask task, String tableTag, int within0to100,
                                          int tableTotal, AtomicInteger lastSaveTime) {
        publishLiveTableProgress(task, tableTag, within0to100, tableTotal, lastSaveTime, false);
    }

    /**
     * @param allowDecrease 进入写入阶段时允许从清表高百分比回落到写入起点（50%）
     */
    private void publishLiveTableProgress(SyncTask task, String tableTag, int within0to100,
                                          int tableTotal, AtomicInteger lastSaveTime,
                                          boolean allowDecrease) {
        if (task == null || task.getId() == null || tableTag == null) {
            return;
        }
        ConcurrentHashMap<String, AtomicInteger> map = liveTableProgressPct.get(task.getId());
        if (map == null) {
            return;
        }
        AtomicInteger cell = map.get(tableTag);
        if (cell == null) {
            return;
        }
        int pct = Math.max(0, Math.min(100, within0to100));
        int prev = cell.get();
        if (pct > prev || pct == 100 || (allowDecrease && pct < prev)) {
            cell.set(pct);
        }
        int overall = calcOverallLiveProgress(task.getId(), tableTotal, -1);
        synchronized (task) {
            Integer cur = task.getProgress();
            if (cur == null || overall != cur) {
                task.setProgress(Math.min(99, Math.max(0, overall)));
            }
            int nowSec = (int) (System.currentTimeMillis() / 1000);
            if (lastSaveTime != null && nowSec - lastSaveTime.get() >= 2) {
                lastSaveTime.set(nowSec);
                saveTaskProgressQuietly(task);
            }
        }
    }

    /** @param doneHint 已完成张数（≥0 时与 map 取较大贡献）；-1 表示忽略 */
    private int calcOverallLiveProgress(Long taskId, int tableTotal, int doneHint) {
        int n = Math.max(tableTotal, 1);
        ConcurrentHashMap<String, AtomicInteger> map = liveTableProgressPct.get(taskId);
        int sum = 0;
        if (map != null && !map.isEmpty()) {
            for (AtomicInteger v : map.values()) {
                sum += v.get();
            }
            return sum / n;
        }
        if (doneHint >= 0) {
            return doneHint * 100 / n;
        }
        return 0;
    }

    private static String trimDataxLine(String line) {
        String t = line == null ? "" : line.trim();
        // 去掉冗长的 logger 前缀，保留业务内容
        int idx = t.indexOf(" - ");
        if (idx > 0 && idx < 120) {
            t = t.substring(idx + 3).trim();
        }
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    private void uiLog(Long taskId, String type, String message) {
        if (taskId == null || message == null || message.isEmpty()) {
            return;
        }
        progressLogBuffer.append(taskId, type, message);
        touchWatchdog();
    }

    private void touchWatchdog() {
        SyncIdleWatchdog wd = TL_WATCHDOG.get();
        if (wd != null) {
            wd.touch(TL_TABLE_TAG.get());
        }
    }

    /**
     * 若同步行数恰好落在常见 SELECT 截断上限（尤其 10 万），提示可能仍受 sql_select_limit/代理限制。
     */
    private void warnIfSuspiciousSelectCap(String tableTag, long rows, SyncTask task) {
        if (!isSuspiciousSelectCap(rows)) {
            return;
        }
        String msg = tableTag + " 写入/扫描行数恰好为 " + rows
                + "，疑似源库 sql_select_limit 或中间件截断 SELECT；"
                + "请核对源表真实条数，必要时在源库执行 SHOW VARIABLES LIKE 'sql_select_limit'";
        log.warn(msg);
        if (task != null && task.getId() != null) {
            uiLog(task.getId(), "warn", msg);
        }
    }

    /** 供单测：常见会话/代理截断上限 */
    static boolean isSuspiciousSelectCap(long rows) {
        if (rows <= 0) {
            return false;
        }
        for (long cap : SUSPICIOUS_SELECT_CAPS) {
            if (rows == cap) {
                return true;
            }
        }
        return false;
    }

    private static String dsLabel(DataSourceConfig ds) {
        if (ds == null) {
            return "?";
        }
        String name = ds.getName() != null ? ds.getName() : ("#" + ds.getId());
        String user = ds.getUsername() != null ? ds.getUsername() : "";
        return name + (user.isEmpty() ? "" : (" / user=" + user));
    }

    private static String safeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "-";
        }
        // 去掉可能嵌入的密码片段
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    /** 日志/UI 展示用，过长列清单截断 */
    private static String shortenSql(String sql, int max) {
        if (sql == null) {
            return "";
        }
        String s = sql.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    private String toFriendlySyncError(Throwable e) {
        String msg = collectThrowableMessages(e);
        String lower = msg.toLowerCase();
        // 须先于 40001/可重试协议判断：锁等待常被标成 SQLState=40001，易误报成 GoldenDB 协议异常
        if (isLockWaitTimeout(e) || lower.contains("lock wait timeout")
                || lower.contains("锁等待超时")) {
            return "清表/写库锁等待超时(1205)：目标表被其他事务占用（与 DELETE 批次大小无关，缩小批次也无法躲开长事务锁）。"
                    + "请在目标库查 PROCESSLIST/长事务后重试；业务低峰再同步；或 DataX「不清空」后手工清表。"
                    + " 详情: " + (msg.length() > 240 ? msg.substring(0, 240) + "..." : msg);
        }
        if (lower.contains("foreign key constraint fails")) {
            return "外键约束阻止操作（已尝试关闭外键检查仍失败，请确认账号权限或先同步子表）: " + msg;
        }
        if (lower.contains("duplicate entry")) {
            return "主键/唯一键冲突: " + msg
                    + "。若刚出现驱动协议异常后重试，请升级至含幂等重试的版本；"
                    + "若目标表未清空，请改用「差异同步」或「增量同步」。";
        }
        if (lower.contains("command denied") || lower.contains("access denied")
                || lower.contains("doesn't have permission")) {
            return "权限不足：当前失败发生在【目标库】写操作（源库只做 SELECT，方向未对调）。"
                    + "数据传输/DML 全量需 DELETE+INSERT；数据同步需 INSERT+UPDATE+DELETE；增量合并需 INSERT+UPDATE。"
                    + " 原始错误: " + msg;
        }
        if (lower.contains("wait_timeout") || lower.contains("communications link failure")
                || lower.contains("connection reset") || lower.contains("broken pipe")) {
            return "数据库连接已断开（常见：清表过久源/目标连接空闲被 wait_timeout 踢掉，"
                    + "或 JumpServer 掐断空闲链路）。请重跑该表（清表不占源连接、清表中期段/结束后池内重借）。"
                    + " 原始错误: " + (msg.length() > 240 ? msg.substring(0, 240) + "..." : msg);
        }
        if (isRetriableJdbcFailure(e)
                || lower.contains("arrayindexoutofbounds")
                || "s1000".equals(lower) || msg.contains("[SQLState=S1000]")) {
            return "数据库协议/连接异常（常见于 GoldenDB 高并发批量写入时驱动解析响应包失败）。"
                    + "已自动丢弃损坏连接；请对该表重跑同步，或在参数设置中适当降低表级并行/批次大小。"
                    + " 详情: " + (msg.length() > 240 ? msg.substring(0, 240) + "..." : msg);
        }
        String jdbcFriendly = com.gcoll.dbcompare.util.JdbcConnectionHelper.toUserMessage(e);
        if (jdbcFriendly != null && !jdbcFriendly.isEmpty()
                && (lower.contains("failed to initialize pool")
                || lower.contains("could not create connection")
                || lower.contains("attempted reconnect")
                || lower.contains("连接失败"))) {
            return jdbcFriendly;
        }
        if (msg.contains("数据源") && msg.contains("连接失败")) {
            return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    /** 展开 cause / SQLException.nextException，避免只显示类名 SQLException。 */
    private String collectThrowableMessages(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                appendSqlExceptionChain(sb, se);
            } else if (cur.getMessage() != null && !cur.getMessage().trim().isEmpty()) {
                String m = cur.getMessage().trim();
                if (sb.indexOf(m) < 0) {
                    if (sb.length() > 0) {
                        sb.append(" ← ");
                    }
                    sb.append(m);
                }
            }
            cur = cur.getCause();
            depth++;
        }
        if (sb.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return sb.toString();
    }

    private void appendSqlExceptionChain(StringBuilder sb, SQLException first) {
        SQLException se = first;
        int n = 0;
        while (se != null && n < 6) {
            String m = se.getMessage();
            if (m != null && !m.trim().isEmpty() && sb.indexOf(m.trim()) < 0) {
                if (sb.length() > 0) {
                    sb.append(" ← ");
                }
                sb.append(m.trim());
                if (se.getSQLState() != null) {
                    sb.append(" [SQLState=").append(se.getSQLState()).append("]");
                }
                if (se.getErrorCode() != 0) {
                    sb.append(" [err=").append(se.getErrorCode()).append("]");
                }
            }
            se = se.getNextException();
            n++;
        }
    }

    private String serializeFailedTables(List<TableSyncResult> failed) {
        if (failed == null || failed.isEmpty()) {
            return "[]";
        }
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (TableSyncResult r : failed) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", r.table);
                m.put("displayName", r.displayName);
                m.put("tableIndex", r.tableIndex);
                m.put("srcCatalog", r.srcCatalog);
                m.put("tgtCatalog", r.tgtCatalog);
                m.put("status", r.status != null ? r.status : OUT_SYNC_FAILED);
                m.put("statusLabel", outcomeLabel(r.status != null ? r.status : OUT_SYNC_FAILED));
                m.put("error", r.error);
                m.put("phase", r.phase);
                m.put("rowCount", r.rowCount);
                m.put("clearAttempted", r.clearAttempted);
                list.add(m);
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("序列化失败表列表失败", e);
            return "[]";
        }
    }

    private String summarizeFailed(List<TableSyncResult> failed) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(5, failed.size());
        for (int i = 0; i < n; i++) {
            TableSyncResult r = failed.get(i);
            if (i > 0) sb.append("; ");
            if (r.tableIndex > 0) {
                sb.append("#").append(r.tableIndex).append(" ");
            }
            sb.append(r.displayName != null ? r.displayName : r.table)
                    .append("[").append(outcomeLabel(r.status)).append("]")
                    .append("(").append(r.phase != null ? r.phase : "?").append("): ")
                    .append(r.error != null ? r.error : "");
        }
        if (failed.size() > n) {
            sb.append("; ...共 ").append(failed.size()).append(" 张待重同步");
        }
        String s = sb.toString();
        return s.length() > 1800 ? s.substring(0, 1800) + "..." : s;
    }

    static class TableSyncResult {
        String table;
        String displayName;
        String srcCatalog;
        String tgtCatalog;
        int tableIndex;
        String error;
        String phase;
        String status;
        boolean clearAttempted;
        long rowCount;
    }
}
