package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.service.CompareReportHtmlSupport;
import com.gcoll.dbcompare.service.CompareService;
import com.gcoll.dbcompare.service.DataSourceService;
import com.gcoll.dbcompare.service.ReportHtmlEncoding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库比对Controller
 * 提供比对任务配置、执行、进度查看和报告功能
 */
@Controller
@RequestMapping("/compare")
@Slf4j
public class CompareController {
    
    @Autowired
    private CompareService compareService;
    
    @Autowired
    private DataSourceService dataSourceService;
    
    /**
     * 比对任务配置页面
     */
    @GetMapping("/config")
    public String configPage(Model model) {
        model.addAttribute("dataSources", dataSourceService.getAllDataSources());
        model.addAttribute("task", new CompareTask());
        return "compare/config";
    }
    
    /**
     * 执行比对任务（AJAX）
     */
    @PostMapping("/execute")
    @ResponseBody
    public Map<String, Object> execute(@RequestBody CompareTask task) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long taskId = compareService.executeCompare(task);
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("msg", "比对任务已启动");
        } catch (Exception e) {
            log.error("执行比对任务失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
    
    /**
     * 比对任务实时进度页面
     */
    @GetMapping("/progress/{taskId}")
    public String progressPage(@PathVariable Long taskId, Model model) {
        model.addAttribute("taskId", taskId);
        return "compare/progress";
    }

    /**
     * 获取比对进度（AJAX 轮询）
     */
    @GetMapping("/progress/{taskId}/data")
    @ResponseBody
    public Map<String, Object> getProgress(@PathVariable Long taskId) {
        return compareService.getProgress(taskId);
    }
    
    /**
     * 查看比对报告：直接输出增强后的 HTML（含顶部菜单 + 提取跳转），不再用 iframe。
     */
    @GetMapping("/report/{taskId}")
    public Object viewReport(@PathVariable Long taskId, Model model) throws Exception {
        CompareTask task = compareService.getTaskById(taskId);
        if ("SUCCESS".equals(task.getStatus()) && task.getReportPath() != null) {
            File reportFile = new File(task.getReportPath());
            if (reportFile.exists() && reportFile.isFile()) {
                String html = ReportHtmlEncoding.readHtml(reportFile.toPath());
                html = CompareReportHtmlSupport.enhance(html, taskId != null ? taskId : 0L);
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", "text/html;charset=UTF-8")
                        .body(html);
            }
        }
        model.addAttribute("task", task);
        model.addAttribute("report", compareService.getReport(taskId));
        return "compare/report";
    }

    /**
     * 查询单表条数差异的具体主键行（AJAX）
     */
    @GetMapping("/row-diff/{taskId}")
    @ResponseBody
    public Map<String, Object> rowDiff(@PathVariable Long taskId,
                                       @RequestParam("table") String table,
                                       @RequestParam(value = "limit", required = false) Integer limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> detail = compareService.findRowDiffDetails(taskId, table, limit);
            result.put("success", true);
            result.put("data", detail);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        } catch (Exception e) {
            log.error("查询行差异失败: taskId={}, table={}", taskId, table, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    static void writeEnhancedReport(HttpServletResponse response, File reportFile, Long taskId)
            throws Exception {
        String html = ReportHtmlEncoding.readHtml(reportFile.toPath());
        html = CompareReportHtmlSupport.enhance(html, taskId != null ? taskId : 0L);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(html);
        response.flushBuffer();
    }

    /**
     * 取消比对任务
     */
    @PostMapping("/cancel/{taskId}")
    @ResponseBody
    public Map<String, Object> cancelTask(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            compareService.cancelTask(taskId);
            result.put("success", true);
            result.put("msg", "任务已取消");
        } catch (Exception e) {
            log.error("取消任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 基于历史任务重新执行比对
     */
    @PostMapping("/reExecute/{taskId}")
    @ResponseBody
    public Map<String, Object> reExecute(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long newTaskId = compareService.reExecute(taskId);
            result.put("success", true);
            result.put("taskId", newTaskId);
            result.put("msg", "任务已重新执行");
        } catch (Exception e) {
            log.error("重新执行任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 删除比对任务
     */
    @DeleteMapping("/delete/{taskId}")
    @ResponseBody
    public Map<String, Object> deleteTask(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            compareService.deleteTask(taskId);
            result.put("success", true);
            result.put("msg", "任务已删除");
        } catch (Exception e) {
            log.error("删除任务失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 获取任务配置（用于复制编辑）
     */
    @GetMapping("/config/{taskId}")
    @ResponseBody
    public Map<String, Object> getTaskConfig(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            CompareTask task = compareService.getTaskById(taskId);
            result.put("success", true);
            result.put("taskName", task.getTaskName());
            result.put("sourceDataSourceId", task.getSourceDataSourceId());
            result.put("targetDataSourceId", task.getTargetDataSourceId());
            result.put("compareMode", task.getCompareMode());
            result.put("sampleRate", task.getSampleRate());
            result.put("compareSchema", task.getCompareSchema());
            result.put("compareCount", task.getCompareCount());
            result.put("compareData", task.getCompareData());
            result.put("compareIndex", task.getCompareIndex());
            result.put("compareFunction", task.getCompareFunction());
            result.put("compareProcedure", task.getCompareProcedure());
            result.put("includeTables", task.getIncludeTables());
            result.put("excludeTables", task.getExcludeTables());
            result.put("sourceSchema", task.getSourceSchema());
            result.put("targetSchema", task.getTargetSchema());
        } catch (Exception e) {
            log.error("获取任务配置失败: {}", taskId, e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
}
