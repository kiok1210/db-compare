package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页控制器
 */
@Controller
public class HomeController {

    @Autowired
    private DatasourceConfigRepository DatasourceConfigRepository;

    @Autowired
    private CompareTaskRepository taskRepository;

    /**
     * 首页
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * 统计API（AJAX）
     */
    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("dataSourceCount", DatasourceConfigRepository.count());
        stats.put("taskCount", taskRepository.count());
        stats.put("successCount", taskRepository.countByStatus("SUCCESS"));
        stats.put("failedCount", taskRepository.countByStatus("FAILED"));
        stats.put("runningCount", taskRepository.countByStatus("RUNNING"));
        return stats;
    }

    /**
     * 任务列表API（AJAX，支持筛选）
     */
    @GetMapping("/api/tasks")
    @ResponseBody
    public Map<String, Object> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        Map<String, Object> result = new HashMap<>();
        List<CompareTask> tasks;

        if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            tasks = taskRepository.findByStatus(status.toUpperCase());
        } else {
            tasks = taskRepository.findTop10ByOrderByCreateTimeDesc();
        }

        // 关键词搜索
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
            m.put("compareMode", t.getCompareMode());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("totalTables", t.getTotalTables());
            m.put("processedTables", t.getProcessedTables());
            m.put("errorMessage", t.getErrorMessage());
            m.put("reportPath", t.getReportPath());
            m.put("createTime", t.getCreateTime());
            m.put("startTime", t.getStartTime());
            m.put("endTime", t.getEndTime());
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("tasks", taskList);
        result.put("total", taskList.size());
        return result;
    }
}
