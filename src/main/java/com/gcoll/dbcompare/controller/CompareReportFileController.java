package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 拦截静态比对报告路径，保证直接打开 /reports/{id}/report.html 也有导航与提取跳转。
 * （同步报告仍走 /reports/sync/** 静态资源）
 */
@Controller
@Slf4j
public class CompareReportFileController {

    private final CompareTaskRepository compareTaskRepository;

    public CompareReportFileController(CompareTaskRepository compareTaskRepository) {
        this.compareTaskRepository = compareTaskRepository;
    }

    @GetMapping("/reports/{taskId:\\d+}/report.html")
    public String serveCompareReport(@PathVariable Long taskId, HttpServletResponse response) throws Exception {
        Path path = Paths.get("reports", String.valueOf(taskId), "report.html");
        File file = path.toFile();
        if (!file.exists()) {
            Optional<CompareTask> opt = compareTaskRepository.findById(taskId);
            if (opt.isPresent() && opt.get().getReportPath() != null) {
                file = new File(opt.get().getReportPath());
            }
        }
        if (!file.exists() || !file.isFile()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "报告不存在");
            return null;
        }
        CompareController.writeEnhancedReport(response, file, taskId);
        return null;
    }
}
