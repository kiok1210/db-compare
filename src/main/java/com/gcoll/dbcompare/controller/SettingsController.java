package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.service.AppSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final AppSettingsService appSettingsService;

    public SettingsController(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    @GetMapping({"", "/"})
    public String page() {
        return "settings/index";
    }

    @GetMapping("/api")
    @ResponseBody
    public Map<String, Object> get() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("settings", appSettingsService.getSettings());
        return result;
    }

    @PostMapping("/api")
    @ResponseBody
    public Map<String, Object> save(@RequestBody AppSettings body) {
        Map<String, Object> result = new HashMap<>();
        try {
            AppSettings saved = appSettingsService.saveSettings(body);
            result.put("success", true);
            result.put("msg", "已保存");
            result.put("settings", saved);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/reset")
    @ResponseBody
    public Map<String, Object> reset() {
        Map<String, Object> result = new HashMap<>();
        try {
            AppSettings saved = appSettingsService.resetToDefaults();
            result.put("success", true);
            result.put("msg", "已恢复默认");
            result.put("settings", saved);
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        return result;
    }
}
