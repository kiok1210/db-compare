package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.service.DataSourceService;
import com.gcoll.dbcompare.service.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库同步 Controller
 */
@Controller
@RequestMapping("/sync")
@Slf4j
public class SyncController {

    private final SyncService syncService;
    private final DataSourceService dataSourceService;

    public SyncController(SyncService syncService, DataSourceService dataSourceService) {
        this.syncService = syncService;
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("tasks", syncService.getAllTasks());
        return "sync/list";
    }

    @GetMapping("/config")
    public String configPage(Model model) {
        model.addAttribute("dataSources", dataSourceService.getAllDataSources());
        model.addAttribute("task", new SyncTask());
        return "sync/config";
    }

    @PostMapping("/execute")
    @ResponseBody
    public Map<String, Object> execute(@RequestBody SyncTask task) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long taskId = syncService.executeSync(task);
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("msg", "同步任务已启动");
        } catch (Exception e) {
            log.error("执行同步任务失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @GetMapping("/progress/{taskId}")
    public String progressPage(@PathVariable Long taskId, Model model) {
        SyncTask task = syncService.getTaskById(taskId);
        // 比对提取的草稿 → 确认页（配置页立即执行的 PENDING 不带「待确认」标记）
        if (syncService.isAwaitingManualStart(task)) {
            return confirmPage(taskId, model);
        }
        model.addAttribute("taskId", taskId);
        return "sync/progress";
    }

    @GetMapping("/confirm/{taskId}")
    public String confirmPage(@PathVariable Long taskId, Model model) {
        SyncTask task = syncService.getTaskById(taskId);
        model.addAttribute("task", task);
        model.addAttribute("taskId", taskId);
        model.addAttribute("dataSources", dataSourceService.getAllDataSources());
        List<String> tables = new ArrayList<>();
        if (task.getIncludeTables() != null) {
            for (String p : task.getIncludeTables().split("[,;\\s]+")) {
                if (p != null && !p.trim().isEmpty()) {
                    tables.add(p.trim());
                }
            }
        }
        model.addAttribute("tables", tables);
        return "sync/confirm";
    }

    /**
     * 更新 PENDING 草稿配置（确认页保存）
     */
    @PostMapping("/draft/{taskId}")
    @ResponseBody
    public Map<String, Object> updateDraft(@PathVariable Long taskId, @RequestBody SyncTask patch) {
        Map<String, Object> result = new HashMap<>();
        try {
            SyncTask saved = syncService.updateDraft(taskId, patch);
            result.put("success", true);
            result.put("taskId", saved.getId());
            result.put("msg", "草稿已保存");
            result.put("includeTables", saved.getIncludeTables());
            result.put("sourceSchema", saved.getSourceSchema());
            result.put("targetSchema", saved.getTargetSchema());
            result.put("schemaMapping", saved.getSchemaMapping());
        } catch (Exception e) {
            log.error("更新同步草稿失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 启动 PENDING 草稿（确认页点「开始同步」）
     */
    @PostMapping("/start/{taskId}")
    @ResponseBody
    public Map<String, Object> startDraft(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long id = syncService.startDraft(taskId);
            result.put("success", true);
            result.put("taskId", id);
            result.put("msg", "同步任务已启动");
        } catch (Exception e) {
            log.error("启动同步草稿失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 从比对报告条数差异创建同步草稿（不自动运行）
     * body: { "tables": ["schema::table",...], "syncMode": "FULL_DML" }
     */
    @PostMapping("/from-compare/{compareTaskId}")
    @ResponseBody
    public Map<String, Object> fromCompareCountDiff(@PathVariable Long compareTaskId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> tables = new ArrayList<>();
            String syncMode = SyncTask.MODE_FULL_DML;
            if (body != null) {
                if (body.get("tables") instanceof List) {
                    for (Object o : (List<?>) body.get("tables")) {
                        if (o != null) {
                            tables.add(String.valueOf(o));
                        }
                    }
                }
                if (body.get("syncMode") != null) {
                    syncMode = String.valueOf(body.get("syncMode"));
                }
            }
            SyncTask draft = syncService.createDraftFromCompareCountDiff(compareTaskId, tables, syncMode);
            result.put("success", true);
            result.put("taskId", draft.getId());
            result.put("msg", "已创建同步草稿（未运行），请确认后启动");
            result.put("confirmUrl", "/sync/confirm/" + draft.getId());
        } catch (Exception e) {
            log.error("从比对提取同步草稿失败: compareTaskId={}", compareTaskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @GetMapping("/progress/{taskId}/data")
    @ResponseBody
    public Map<String, Object> getProgress(@PathVariable Long taskId,
                                           @RequestParam(defaultValue = "0") long sinceLogSeq) {
        return syncService.getProgress(taskId, sinceLogSeq);
    }

    /**
     * 查看同步报告：文件存在则重定向到静态 HTML，否则按任务结果补生成后再跳转。
     */
    @GetMapping("/report/{taskId}")
    public String viewReport(@PathVariable Long taskId, Model model) {
        try {
            String path = syncService.ensureReport(taskId);
            if (path != null && new java.io.File(path).exists()) {
                return "redirect:/" + path.replace("\\", "/");
            }
        } catch (Exception e) {
            log.warn("打开同步报告失败 taskId={}: {}", taskId, e.getMessage());
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("taskId", taskId);
        model.addAttribute("task", syncService.getTaskById(taskId));
        return "sync/report";
    }

    @PostMapping("/cancel/{taskId}")
    @ResponseBody
    public Map<String, Object> cancelTask(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean cancelled = syncService.cancelTask(taskId);
            result.put("success", cancelled);
            result.put("msg", cancelled ? "任务已取消" : "任务无法取消（可能已结束）");
        } catch (Exception e) {
            log.error("取消同步任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/reExecute/{taskId}")
    @ResponseBody
    public Map<String, Object> reExecute(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long newTaskId = syncService.reExecute(taskId);
            result.put("success", true);
            result.put("taskId", newTaskId);
            result.put("msg", "同步任务已重新启动");
        } catch (Exception e) {
            log.error("重新执行同步任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 对失败表重新同步
     * body: { "tables": ["schema::table", ...] }；tables 为空则重同步全部失败表
     */
    @PostMapping("/resync-failed/{taskId}")
    @ResponseBody
    public Map<String, Object> resyncFailed(@PathVariable Long taskId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> tables = new ArrayList<>();
            if (body != null && body.get("tables") instanceof List) {
                for (Object o : (List<?>) body.get("tables")) {
                    if (o != null) tables.add(String.valueOf(o));
                }
            }
            if (tables.isEmpty()) {
                // 未指定则用全部失败表
                Map<String, Object> progress = syncService.getProgress(taskId);
                Object failed = progress.get("failedTables");
                if (failed instanceof List) {
                    for (Object item : (List<?>) failed) {
                        if (item instanceof Map && ((Map<?, ?>) item).get("table") != null) {
                            tables.add(String.valueOf(((Map<?, ?>) item).get("table")));
                        }
                    }
                }
            }
            Long newTaskId = syncService.reSyncFailedTables(taskId, tables);
            result.put("success", true);
            result.put("taskId", newTaskId);
            result.put("confirmUrl", "/sync/confirm/" + newTaskId);
            result.put("msg", "已创建待确认任务（" + tables.size() + " 张表），请确认配置后再启动同步");
        } catch (Exception e) {
            log.error("待重同步表一键同步失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/delete/{taskId}")
    @ResponseBody
    public Map<String, Object> deleteTask(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            syncService.deleteTask(taskId);
            result.put("success", true);
            result.put("msg", "任务已删除");
        } catch (Exception e) {
            log.error("删除同步任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @GetMapping("/api/tasks")
    @ResponseBody
    public Map<String, Object> apiTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        Map<String, Object> result = new HashMap<>();
        List<SyncTask> tasks = syncService.getAllTasks();

        if (status != null && !status.isEmpty()) {
            tasks = tasks.stream()
                    .filter(t -> status.equalsIgnoreCase(t.getStatus()))
                    .collect(Collectors.toList());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            tasks = tasks.stream()
                    .filter(t -> t.getTaskName() != null && t.getTaskName().toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> taskList = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("taskName", t.getTaskName());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("totalTables", t.getTotalTables());
            m.put("processedTables", t.getProcessedTables());
            m.put("errorMessage", t.getErrorMessage());
            m.put("createTime", t.getCreateTime());
            m.put("startTime", t.getStartTime());
            m.put("endTime", t.getEndTime());
            m.put("currentTable", t.getCurrentTable());
            m.put("reportPath", t.getReportPath());
            m.put("hasReport", t.getReportPath() != null && !t.getReportPath().isEmpty());
            m.put("successTables", t.getSuccessTables());
            m.put("failedTablesCount", t.getFailedTablesCount());
            m.put("awaitingStart", SyncTask.STATUS_PENDING.equals(t.getStatus()) && t.getStartTime() == null);
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("tasks", taskList);
        result.put("total", taskList.size());
        return result;
    }
}
