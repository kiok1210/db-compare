package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.WindowsDriveCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本机 C 盘清理（独立功能页）。
 */
@Controller
@RequestMapping("/cleanup")
@Slf4j
public class OsCleanupController {

    @Autowired
    private WindowsDriveCleanupService cleanupService;

    @GetMapping("")
    public String page(Model model) {
        String os = System.getProperty("os.name", "");
        boolean windows = cleanupService.isWindows();
        model.addAttribute("windows", windows);
        model.addAttribute("linux", os.toLowerCase(Locale.ROOT).contains("linux"));
        model.addAttribute("osName", os);
        Map<String, Object> st = cleanupService.status();
        long free = toLong(st == null ? null : st.get("freeBytes"));
        long total = toLong(st == null ? null : st.get("totalBytes"));
        model.addAttribute("freeGb", formatGb(free));
        model.addAttribute("totalGb", formatGb(total));
        model.addAttribute("usedGb", formatGb(Math.max(0L, total - free)));
        return "cleanup/index";
    }

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        return cleanupService.status();
    }

    @GetMapping("/progress")
    @ResponseBody
    public WindowsDriveCleanupService.ProgressSnapshot progress() {
        return cleanupService.progress();
    }

    @GetMapping("/usage")
    @ResponseBody
    public Map<String, Object> usage() {
        return cleanupService.usage();
    }

    @PostMapping("/audit")
    @ResponseBody
    public Object audit(@RequestBody(required = false) WindowsDriveCleanupService.Request request) {
        try {
            WindowsDriveCleanupService.Request req = request == null
                    ? new WindowsDriveCleanupService.Request() : request;
            req.setMode(WindowsDriveCleanupService.Mode.AUDIT);
            req.setConfirmClean(false);
            return cleanupService.run(req);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.error("C 盘审计失败", e);
            return error(e.getMessage() != null ? e.getMessage() : "审计失败");
        }
    }

    @PostMapping("/clean")
    @ResponseBody
    public Object clean(@RequestBody WindowsDriveCleanupService.Request request) {
        try {
            if (request == null) {
                return error("请求不能为空");
            }
            request.setMode(WindowsDriveCleanupService.Mode.CLEAN);
            return cleanupService.run(request);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.error("C 盘清理失败", e);
            return error(e.getMessage() != null ? e.getMessage() : "清理失败");
        }
    }

    private static long toLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return 0L;
    }

    private static String formatGb(long bytes) {
        return String.format(Locale.US, "%.2f", bytes / 1073741824.0);
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("msg", msg);
        return r;
    }
}
