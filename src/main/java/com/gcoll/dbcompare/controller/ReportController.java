package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.repository.SyncTaskRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 任务报告列表（比对 + 同步）
 */
@Controller
public class ReportController {

    private final CompareTaskRepository compareTaskRepository;
    private final SyncTaskRepository syncTaskRepository;

    public ReportController(CompareTaskRepository compareTaskRepository,
                            SyncTaskRepository syncTaskRepository) {
        this.compareTaskRepository = compareTaskRepository;
        this.syncTaskRepository = syncTaskRepository;
    }

    @GetMapping("/report/list")
    public String list(@RequestParam(required = false, defaultValue = "compare") String tab, Model model) {
        List<CompareTask> compareTasks = compareTaskRepository.findTop10ByOrderByCreateTimeDesc();
        List<SyncTask> syncTasks = syncTaskRepository.findAllByOrderByCreateTimeDesc();
        if (syncTasks.size() > 50) {
            syncTasks = syncTasks.subList(0, 50);
        }
        model.addAttribute("compareTasks", compareTasks);
        model.addAttribute("syncTasks", syncTasks);
        model.addAttribute("tab", "sync".equalsIgnoreCase(tab) ? "sync" : "compare");
        // 兼容旧模板变量
        model.addAttribute("tasks", compareTasks);
        return "report/list";
    }
}
