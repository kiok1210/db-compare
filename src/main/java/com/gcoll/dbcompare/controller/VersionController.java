package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.AppVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用版本与更新日志 API
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    private final AppVersionService appVersionService;

    public VersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    @GetMapping
    public Map<String, Object> current() {
        return appVersionService.getVersionInfo();
    }

    @GetMapping("/changelog")
    public Map<String, Object> changelog() {
        return appVersionService.getChangelog();
    }

    @PostMapping("/ack-db-update")
    public Map<String, Object> ackDbUpdate() {
        appVersionService.acknowledgeDbUpdate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
