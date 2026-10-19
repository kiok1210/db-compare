package com.gcoll.dbcompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.repository.SyncTaskRepository;
import com.gcoll.dbcompare.util.SchemaMappingHelper;
import com.gcoll.dbcompare.util.TaskNameHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SyncService {

    private final SyncTaskRepository syncTaskRepository;
    private final CompareTaskRepository compareTaskRepository;
    private final SyncTaskRunner syncTaskRunner;
    private final ReportService reportService;
    private final AppSettingsService appSettingsService;
    private final SyncProgressLogBuffer progressLogBuffer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SyncService(SyncTaskRepository syncTaskRepository,
                       CompareTaskRepository compareTaskRepository,
                       SyncTaskRunner syncTaskRunner,
                       ReportService reportService,
                       AppSettingsService appSettingsService,
                       SyncProgressLogBuffer progressLogBuffer) {
        this.syncTaskRepository = syncTaskRepository;
        this.compareTaskRepository = compareTaskRepository;
        this.syncTaskRunner = syncTaskRunner;
        this.reportService = reportService;
        this.appSettingsService = appSettingsService;
        this.progressLogBuffer = progressLogBuffer;
    }

    /**
     * 创建并立即异步执行同步任务（配置页「执行」）
     */
    public Long executeSync(SyncTask task) {
        SyncTask saved = createDraft(task);
        syncTaskRunner.run(saved.getId());
        return saved.getId();
    }

    /**
     * 仅创建草稿（PENDING），不启动执行 — 供比对提取后人工确认。
     */
    public SyncTask createDraft(SyncTask task) {
        task.setStatus(SyncTask.STATUS_PENDING);
        task.setProgress(0);
        task.setProcessedTables(0);
        task.setTotalTables(0);
        task.setSuccessTables(0);
        task.setFailedTablesCount(0);
        task.setFailedTables(null);
        task.setErrorMessage(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setReportPath(null);
        com.gcoll.dbcompare.entity.AppSettings defaults = appSettingsService.getSettings();
        task.setSyncMode(SyncTask.normalizeMode(
                task.getSyncMode() != null ? task.getSyncMode() : defaults.getSyncDefaultMode()));
        int tableCount = countIncludeTables(task.getIncludeTables());
        if (task.getParallelThreads() == null || task.getParallelThreads() < 1) {
            if (tableCount > 0) {
                task.setParallelThreads(SyncTask.defaultParallelForTableCount(tableCount));
            } else {
                task.setParallelThreads(defaults.getSyncParallelThreads());
            }
        } else {
            task.setParallelThreads(SyncTask.clampParallelThreads(task.getParallelThreads()));
        }
        if (task.getBatchSize() == null || task.getBatchSize() < 1) {
            task.setBatchSize(defaults.getSyncBatchSize());
        }
        return syncTaskRepository.save(task);
    }

    /**
     * 启动已存在的 PENDING 草稿任务。
     */
    public Long startDraft(Long taskId) {
        SyncTask task = getTaskById(taskId);
        if (!SyncTask.STATUS_PENDING.equals(task.getStatus())) {
            throw new RuntimeException("仅待确认(PENDING)任务可启动，当前状态: " + task.getStatus());
        }
        if (task.getStartTime() != null) {
            throw new RuntimeException("任务已在执行中或已启动");
        }
        syncTaskRunner.run(task.getId());
        return task.getId();
    }

    /**
     * 从比对任务的条数差异表创建同步草稿（不自动运行）。
     * tables：比对报告中的表名，建议 schema::table；为空则拒绝。
     */
    public SyncTask createDraftFromCompareCountDiff(Long compareTaskId, List<String> tables, String syncMode) {
        CompareTask compare = compareTaskRepository.findById(compareTaskId)
                .orElseThrow(() -> new RuntimeException("比对任务不存在: " + compareTaskId));
        if (compare.getSourceDataSourceId() == null || compare.getTargetDataSourceId() == null) {
            throw new RuntimeException("比对任务缺少源/目标数据源，无法创建同步任务");
        }
        List<String> selected = normalizeTableKeys(tables);
        if (selected.isEmpty()) {
            throw new RuntimeException("请至少选择一张条数差异表");
        }

        String baseName = compare.getTaskName() != null ? compare.getTaskName() : ("比对#" + compareTaskId);
        String taskName = baseName + " - 条数差异同步(" + selected.size() + ")";
        if (taskName.length() > 200) {
            taskName = taskName.substring(0, 200);
        }

        SyncTask sync = new SyncTask();
        sync.setTaskName(taskName);
        sync.setSourceDataSourceId(compare.getSourceDataSourceId());
        sync.setTargetDataSourceId(compare.getTargetDataSourceId());
        sync.setSourceSchema(truncate(firstNonBlank(schemasFromTableKeys(selected), compare.getSourceSchema()), 500));
        sync.setTargetSchema(truncate(compare.getTargetSchema(), 500));
        sync.setSchemaMapping(compare.getSchemaMapping());
        sync.setIncludeTables(String.join(",", selected));
        sync.setExcludeTables(null);
        applySchemaScopeFromTables(sync, selected);
        com.gcoll.dbcompare.entity.AppSettings defaults = appSettingsService.getSettings();
        sync.setSyncMode(SyncTask.normalizeMode(syncMode != null ? syncMode : defaults.getSyncDefaultMode()));
        sync.setBatchSize(defaults.getSyncBatchSize());
        sync.setParallelThreads(SyncTask.defaultParallelForTableCount(selected.size()));
        String pending = "待确认：比对#" + compareTaskId + " 条数差异 " + selected.size() + " 张表";
        sync.setCurrentTable(pending);
        SyncTask saved = createDraft(sync);
        if (saved.getId() == null) {
            throw new RuntimeException("同步草稿保存失败（未生成任务 ID）");
        }
        return saved;
    }

    /**
     * 更新尚未启动的 PENDING 草稿（确认页编辑）。
     */
    public SyncTask updateDraft(Long taskId, SyncTask patch) {
        SyncTask task = getTaskById(taskId);
        if (!SyncTask.STATUS_PENDING.equals(task.getStatus()) || task.getStartTime() != null) {
            throw new RuntimeException("仅未启动的待确认任务可编辑，当前状态: " + task.getStatus());
        }
        if (patch.getTaskName() != null && !patch.getTaskName().trim().isEmpty()) {
            String name = patch.getTaskName().trim();
            task.setTaskName(name.length() > 200 ? name.substring(0, 200) : name);
        }
        if (patch.getSourceDataSourceId() != null) {
            task.setSourceDataSourceId(patch.getSourceDataSourceId());
        }
        if (patch.getTargetDataSourceId() != null) {
            task.setTargetDataSourceId(patch.getTargetDataSourceId());
        }
        if (patch.getSourceSchema() != null) {
            task.setSourceSchema(truncate(patch.getSourceSchema(), 500));
        }
        if (patch.getTargetSchema() != null) {
            task.setTargetSchema(truncate(patch.getTargetSchema(), 500));
        }
        if (patch.getSchemaMapping() != null) {
            String mapping = patch.getSchemaMapping().trim();
            if (mapping.isEmpty()) {
                String src = patch.getSourceSchema() != null ? patch.getSourceSchema() : task.getSourceSchema();
                String tgt = patch.getTargetSchema() != null ? patch.getTargetSchema() : task.getTargetSchema();
                mapping = SchemaMappingHelper.buildAutoSchemaMapping(src, tgt, task.getSchemaMapping());
            }
            task.setSchemaMapping(mapping.isEmpty() ? null : mapping);
        }
        // 多库场景兜底：无映射时按目标列表推断，避免 DELETE/INSERT 打到源库同名
        ensureSchemaMapping(task);
        if (patch.getIncludeTables() != null) {
            List<String> keys = normalizeTableKeys(java.util.Arrays.asList(patch.getIncludeTables().split("[,;\\s]+")));
            if (keys.isEmpty()) {
                throw new RuntimeException("请至少保留一张待同步表");
            }
            task.setIncludeTables(String.join(",", keys));
        }
        if (patch.getExcludeTables() != null) {
            task.setExcludeTables(patch.getExcludeTables().trim().isEmpty() ? null : patch.getExcludeTables().trim());
        }
        if (patch.getSyncMode() != null) {
            task.setSyncMode(SyncTask.normalizeMode(patch.getSyncMode()));
        }
        if (patch.getBatchSize() != null && patch.getBatchSize() >= 1) {
            task.setBatchSize(patch.getBatchSize());
        }
        if (patch.getParallelThreads() != null) {
            task.setParallelThreads(SyncTask.clampParallelThreads(patch.getParallelThreads()));
        }
        if (patch.getDataxClearMode() != null) {
            String cm = patch.getDataxClearMode().trim();
            task.setDataxClearMode(cm.isEmpty() ? null : cm);
        }
        if (patch.getClearParallelThreads() != null) {
            task.setClearParallelThreads(SyncTask.clampClearParallel(patch.getClearParallelThreads()));
        }
        int tableCount = 0;
        if (task.getIncludeTables() != null && !task.getIncludeTables().isEmpty()) {
            tableCount = task.getIncludeTables().split(",").length;
        }
        task.setCurrentTable("待确认：条数差异草稿 " + tableCount + " 张表");
        return syncTaskRepository.save(task);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return a;
    }

    /** 从 schema::table 列表推导源库名列表 */
    static String schemasFromTableKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        for (String k : keys) {
            if (k == null) {
                continue;
            }
            int idx = k.indexOf("::");
            if (idx > 0) {
                schemas.add(k.substring(0, idx).trim());
            }
        }
        return schemas.isEmpty() ? null : String.join(",", schemas);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 统计 includeTables CSV 中的表数量（空串不计） */
    static int countIncludeTables(String includeTables) {
        if (includeTables == null || includeTables.trim().isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String part : includeTables.split("[,;\\s]+")) {
            if (part != null && !part.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    static List<String> normalizeTableKeys(List<String> tables) {
        Set<String> set = new LinkedHashSet<>();
        if (tables == null) {
            return new ArrayList<>();
        }
        for (String t : tables) {
            if (t == null) {
                continue;
            }
            String s = t.trim();
            if (!s.isEmpty()) {
                set.add(s);
            }
        }
        return new ArrayList<>(set);
    }

    public SyncTask getTaskById(Long taskId) {
        return syncTaskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("同步任务不存在: " + taskId));
    }

    public List<SyncTask> getAllTasks() {
        return syncTaskRepository.findAllByOrderByCreateTimeDesc();
    }

    public Map<String, Object> getProgress(Long taskId) {
        return getProgress(taskId, 0L);
    }

    public Map<String, Object> getProgress(Long taskId, long sinceLogSeq) {
        SyncTask task = getTaskById(taskId);
        Map<String, Object> progress = new HashMap<>();
        progress.put("status", task.getStatus());
        progress.put("progress", task.getProgress());
        progress.put("currentTable", task.getCurrentTable());
        progress.put("processedTables", task.getProcessedTables());
        progress.put("totalTables", task.getTotalTables());
        progress.put("successTables", task.getSuccessTables() != null ? task.getSuccessTables() : 0);
        progress.put("failedTablesCount", task.getFailedTablesCount() != null ? task.getFailedTablesCount() : 0);
        progress.put("failedTables", parseFailedTables(task.getFailedTables()));
        progress.put("errorMessage", task.getErrorMessage());
        progress.put("taskName", task.getTaskName());
        progress.put("syncMode", task.getSyncMode());
        progress.put("parallelThreads", SyncTask.clampParallelThreads(task.getParallelThreads()));
        progress.put("dataxClearMode", task.getDataxClearMode());
        progress.put("clearParallelThreads",
                task.getClearParallelThreads() != null
                        ? SyncTask.clampClearParallel(task.getClearParallelThreads()) : null);
        progress.put("batchSize", task.getBatchSize());
        progress.put("includeTables", task.getIncludeTables());
        progress.put("sourceSchema", task.getSourceSchema());
        progress.put("targetSchema", task.getTargetSchema());
        progress.put("schemaMapping", task.getSchemaMapping());
        progress.put("startTime", task.getStartTime() != null ? task.getStartTime().toString() : null);
        progress.put("endTime", task.getEndTime() != null ? task.getEndTime().toString() : null);
        progress.put("reportPath", task.getReportPath());
        progress.put("hasReport", reportFileExists(task));
        progress.put("awaitingStart", isAwaitingManualStart(task));
        progress.put("logs", progressLogBuffer.since(taskId, sinceLogSeq));
        return progress;
    }

    /**
     * 比对提取的草稿：PENDING 且尚未 start，需人工确认后 /sync/start。
     * 配置页「立即执行」创建的 PENDING 也会短暂存在，但不带「待确认」标记，不应进确认页。
     */
    public boolean isAwaitingManualStart(SyncTask task) {
        if (task == null || !SyncTask.STATUS_PENDING.equals(task.getStatus()) || task.getStartTime() != null) {
            return false;
        }
        String cur = task.getCurrentTable();
        return cur != null && cur.startsWith("待确认");
    }

    public boolean reportFileExists(SyncTask task) {
        if (task == null || task.getReportPath() == null || task.getReportPath().trim().isEmpty()) {
            return false;
        }
        return new java.io.File(task.getReportPath()).exists();
    }

    /**
     * 确保有同步报告：已有且含一键重同步控件则复用；否则按任务字段生成/升级报告。
     * 补生成时成功表明细可能为空（仅库中有 failedTables）。
     */
    public String ensureReport(Long taskId) {
        SyncTask task = getTaskById(taskId);
        if (reportFileExists(task) && reportHasResyncAction(task)) {
            return task.getReportPath();
        }
        try {
            List<Map<String, Object>> failed = parseFailedTables(task.getFailedTables());
            String path = reportService.generateSyncReport(task, new ArrayList<>(), failed);
            task.setReportPath(path);
            syncTaskRepository.save(task);
            return path;
        } catch (Exception e) {
            throw new RuntimeException("生成同步报告失败: " + e.getMessage(), e);
        }
    }

    private boolean reportHasResyncAction(SyncTask task) {
        try {
            List<Map<String, Object>> failed = parseFailedTables(task.getFailedTables());
            if (failed.isEmpty()) {
                return true; // 无需按钮
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(task.getReportPath()));
            String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            // 旧报告无应用导航/确认页跳转时需重新生成
            return html.contains("id=\"resyncFailedBtn\"")
                    && html.contains("confirm-dialog.js")
                    && html.contains("rpt-header")
                    && html.contains("/sync/confirm/")
                    && !html.contains("if(!confirm(");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取消正在运行的同步任务
     */
    public boolean cancelTask(Long taskId) {
        SyncTask task = syncTaskRepository.findById(taskId).orElse(null);
        if (task != null && SyncTask.STATUS_RUNNING.equals(task.getStatus())) {
            syncTaskRunner.requestCancel(taskId);
            task.setStatus(SyncTask.STATUS_CANCELLED);
            task.setEndTime(java.time.LocalDateTime.now());
            syncTaskRepository.save(task);
            return true;
        }
        return false;
    }

    /**
     * 基于历史任务配置重新执行同步
     */
    public Long reExecute(Long taskId) {
        SyncTask original = getTaskById(taskId);
        SyncTask newTask = copyBaseConfig(original);
        newTask.setTaskName(TaskNameHelper.buildReExecuteName(original.getTaskName()));
        newTask.setIncludeTables(original.getIncludeTables());
        newTask.setExcludeTables(original.getExcludeTables());
        return executeSync(newTask);
    }

    /**
     * 对待重同步表重新提取元信息并同步（未清理 / 清理失败 / 同步失败 均可）。
     * tables 为空则重同步全部待重同步表。
     */
    public Long reSyncFailedTables(Long taskId, List<String> tables) {
        SyncTask original = getTaskById(taskId);
        List<Map<String, Object>> failed = parseFailedTables(original.getFailedTables());
        if (failed.isEmpty()) {
            throw new RuntimeException("该任务没有可重同步的表（未清理/清理失败/同步失败）");
        }

        java.util.Set<String> allowed = failed.stream()
                .map(m -> String.valueOf(m.get("table")))
                .collect(Collectors.toSet());
        List<String> selected;
        if (tables == null || tables.isEmpty()) {
            selected = allowed.stream()
                    .filter(t -> t != null && !t.trim().isEmpty() && !"?".equals(t) && !"unknown".equalsIgnoreCase(t))
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            selected = tables.stream()
                    .filter(t -> t != null && !t.trim().isEmpty())
                    .map(String::trim)
                    .filter(t -> !"?".equals(t) && !"unknown".equalsIgnoreCase(t))
                    .filter(allowed::contains)
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (selected.isEmpty()) {
            throw new RuntimeException("所选表不在待重同步列表中，或失败记录缺少有效表名（请用原任务勾选表重新提取同步）");
        }

        SyncTask newTask = copyBaseConfig(original);
        newTask.setTaskName(original.getTaskName() + " - 一键重同步(" + selected.size() + ")");
        newTask.setIncludeTables(String.join(",", selected));
        newTask.setExcludeTables(null);
        // 库范围按所选表自动收窄，并补全/收窄 schemaMapping（避免确认页清空映射后打到源库同名）
        applySchemaScopeFromTables(newTask, selected);
        newTask.setCurrentTable("待确认：一键重同步 " + selected.size() + " 张表");
        SyncTask saved = createDraft(newTask);
        if (saved.getId() == null) {
            throw new RuntimeException("重同步草稿保存失败（未生成任务 ID）");
        }
        return saved.getId();
    }

    /**
     * 按所选 schema::table 收窄源库，并据已有映射/目标库列表补全 schemaMapping 与目标库范围。
     */
    static void applySchemaScopeFromTables(SyncTask task, List<String> selected) {
        String srcSchemas = schemasFromTableKeys(selected);
        if (srcSchemas != null) {
            task.setSourceSchema(srcSchemas);
        }
        String mapping = SchemaMappingHelper.buildAutoSchemaMapping(
                task.getSourceSchema(), task.getTargetSchema(), task.getSchemaMapping());
        if (mapping != null && !mapping.isEmpty()) {
            task.setSchemaMapping(mapping);
            String tgtFromMap = SchemaMappingHelper.targetSchemasFromMapping(mapping);
            if (tgtFromMap != null && !tgtFromMap.isEmpty()) {
                task.setTargetSchema(tgtFromMap);
            }
        }
    }

    private static void ensureSchemaMapping(SyncTask task) {
        if (task == null) {
            return;
        }
        String src = task.getSourceSchema();
        String tgt = task.getTargetSchema();
        if (src == null || !src.contains(",") || tgt == null || tgt.trim().isEmpty()) {
            // 单源或无目标时，仅在映射为空且可推断时补全
            if (task.getSchemaMapping() != null && !task.getSchemaMapping().trim().isEmpty()) {
                return;
            }
        }
        String mapping = SchemaMappingHelper.buildAutoSchemaMapping(src, tgt, task.getSchemaMapping());
        if (mapping != null && !mapping.isEmpty()) {
            task.setSchemaMapping(mapping);
        }
    }

    private SyncTask copyBaseConfig(SyncTask original) {
        SyncTask newTask = new SyncTask();
        newTask.setSourceDataSourceId(original.getSourceDataSourceId());
        newTask.setTargetDataSourceId(original.getTargetDataSourceId());
        newTask.setSourceSchema(original.getSourceSchema());
        newTask.setTargetSchema(original.getTargetSchema());
        newTask.setSchemaMapping(original.getSchemaMapping());
        newTask.setSyncMode(SyncTask.normalizeMode(original.getSyncMode()));
        newTask.setBatchSize(original.getBatchSize());
        newTask.setParallelThreads(original.getParallelThreads());
        newTask.setDataxClearMode(original.getDataxClearMode());
        newTask.setClearParallelThreads(original.getClearParallelThreads());
        return newTask;
    }

    public List<Map<String, Object>> parseFailedTables(String json) {
        if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void deleteTask(Long taskId) {
        syncTaskRepository.deleteById(taskId);
    }
}
