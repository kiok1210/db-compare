package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.WindowsDriveCleanupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OsCleanupController.class)
@AutoConfigureMockMvc(addFilters = false)
class OsCleanupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WindowsDriveCleanupService cleanupService;

    @Test
    void page_containsStandaloneCleanupControls() throws Exception {
        when(cleanupService.isWindows()).thenReturn(true);
        Map<String, Object> st = new LinkedHashMap<String, Object>();
        st.put("success", true);
        st.put("windows", true);
        st.put("freeBytes", 4L * 1024 * 1024 * 1024);
        st.put("totalBytes", 146L * 1024 * 1024 * 1024);
        when(cleanupService.status()).thenReturn(st);
        mockMvc.perform(get("/cleanup"))
                .andExpect(status().isOk())
                .andExpect(view().name("cleanup/index"))
                .andExpect(content().string(containsString("C 盘清理")))
                .andExpect(content().string(containsString("href=\"/cleanup\"")))
                .andExpect(content().string(containsString("先审计（不删除）")))
                .andExpect(content().string(containsString("执行清理")))
                .andExpect(content().string(containsString("不会删除")))
                .andExpect(content().string(containsString("confirmClean")))
                .andExpect(content().string(containsString("/cleanup/audit")))
                .andExpect(content().string(containsString("/cleanup/clean")))
                .andExpect(content().string(containsString("/cleanup/progress")))
                .andExpect(content().string(containsString("progressBar")))
                .andExpect(content().string(containsString("/cleanup/usage")))
                .andExpect(content().string(containsString("C 盘为什么腾不出空间")))
                .andExpect(content().string(containsString("文件明细")))
                .andExpect(content().string(containsString("大文件")))
                .andExpect(content().string(containsString("重复文件")))
                .andExpect(content().string(containsString("微信缓存")))
                .andExpect(content().string(containsString("includeWeChat")))
                .andExpect(content().string(containsString("deleteDupExtras")))
                .andExpect(content().string(containsString("actionBar")))
                .andExpect(content().string(containsString("展开占用明细")))
                .andExpect(content().string(containsString("usageDetails")))
                .andExpect(content().string(containsString("GB 可用")))
                .andExpect(content().string(containsString("不在打开时全盘扫描")))
                .andExpect(content().string(containsString("timeout: 600000")));
        String html = mockMvc.perform(get("/cleanup")).andReturn().getResponse().getContentAsString();
        assertTrue(html.indexOf("id=\"btnAudit\"") < html.indexOf("id=\"usageCard\""),
                "操作按钮必须在占用明细之前，避免被长列表顶出首屏");
    }

    @Test
    void page_nonWindows_showsUnavailable() throws Exception {
        when(cleanupService.isWindows()).thenReturn(false);
        Map<String, Object> st = new LinkedHashMap<String, Object>();
        st.put("success", true);
        st.put("windows", false);
        st.put("freeBytes", 0L);
        st.put("totalBytes", 0L);
        when(cleanupService.status()).thenReturn(st);
        mockMvc.perform(get("/cleanup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("无法清理 C 盘")));
    }

    @Test
    void usage_returnsOccupancy() throws Exception {
        Map<String, Object> u = new LinkedHashMap<String, Object>();
        u.put("success", true);
        u.put("reclaimableMb", 12.5);
        u.put("hint", "白名单只能清缓存");
        when(cleanupService.usage()).thenReturn(u);
        mockMvc.perform(get("/cleanup/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reclaimableMb").value(12.5));
    }

    @Test
    void status_returnsDriveSpace() throws Exception {
        Map<String, Object> st = new LinkedHashMap<String, Object>();
        st.put("success", true);
        st.put("windows", true);
        st.put("freeBytes", 123L);
        st.put("totalBytes", 456L);
        when(cleanupService.status()).thenReturn(st);
        mockMvc.perform(get("/cleanup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.windows").value(true))
                .andExpect(jsonPath("$.freeBytes").value(123));
    }

    @Test
    void audit_forcesAuditMode() throws Exception {
        WindowsDriveCleanupService.Report report = new WindowsDriveCleanupService.Report();
        report.setSuccess(true);
        report.setMode("AUDIT");
        report.setTargets(Collections.<WindowsDriveCleanupService.TargetRow>emptyList());
        when(cleanupService.run(any(WindowsDriveCleanupService.Request.class))).thenReturn(report);

        mockMvc.perform(post("/cleanup/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"DEEP\",\"minAgeDays\":14,\"includeBrowserCaches\":true,\"confirmClean\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.mode").value("AUDIT"));

        ArgumentCaptor<WindowsDriveCleanupService.Request> cap =
                ArgumentCaptor.forClass(WindowsDriveCleanupService.Request.class);
        verify(cleanupService).run(cap.capture());
        assertEquals(WindowsDriveCleanupService.Mode.AUDIT, cap.getValue().getMode());
        assertFalse(cap.getValue().isConfirmClean());
        assertEquals(WindowsDriveCleanupService.Preset.DEEP, cap.getValue().getPreset());
        assertEquals(Integer.valueOf(14), cap.getValue().getMinAgeDays());
        assertTrue(cap.getValue().isIncludeBrowserCaches());
    }

    @Test
    void clean_withoutConfirm_returnsJsonError() throws Exception {
        when(cleanupService.run(any(WindowsDriveCleanupService.Request.class)))
                .thenThrow(new IllegalArgumentException("清理需先勾选确认（confirmClean=true）"));
        mockMvc.perform(post("/cleanup/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"STANDARD\",\"confirmClean\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("确认")));
    }

    @Test
    void clean_withConfirm_runsCleanMode() throws Exception {
        WindowsDriveCleanupService.Report report = new WindowsDriveCleanupService.Report();
        report.setSuccess(true);
        report.setMode("CLEAN");
        report.setDeletedFiles(2);
        when(cleanupService.run(any(WindowsDriveCleanupService.Request.class))).thenReturn(report);

        mockMvc.perform(post("/cleanup/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"STANDARD\",\"confirmClean\":true,\"minAgeDays\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.mode").value("CLEAN"))
                .andExpect(jsonPath("$.deletedFiles").value(2));

        ArgumentCaptor<WindowsDriveCleanupService.Request> cap =
                ArgumentCaptor.forClass(WindowsDriveCleanupService.Request.class);
        verify(cleanupService).run(cap.capture());
        assertEquals(WindowsDriveCleanupService.Mode.CLEAN, cap.getValue().getMode());
        assertTrue(cap.getValue().isConfirmClean());
    }

    @Test
    void progress_returnsSnapshot() throws Exception {
        WindowsDriveCleanupService.ProgressSnapshot snap = new WindowsDriveCleanupService.ProgressSnapshot();
        snap.setRunning(true);
        snap.setPercent(42);
        snap.setMessage("扫描 User TEMP");
        snap.setCurrentPath("C:\\\\Temp\\\\a.tmp");
        when(cleanupService.progress()).thenReturn(snap);
        mockMvc.perform(get("/cleanup/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.percent").value(42))
                .andExpect(jsonPath("$.message").value("扫描 User TEMP"));
    }

    @Test
    void audit_emptyBody_usesDefaults() throws Exception {
        WindowsDriveCleanupService.Report report = new WindowsDriveCleanupService.Report();
        report.setSuccess(true);
        when(cleanupService.run(any(WindowsDriveCleanupService.Request.class))).thenReturn(report);
        mockMvc.perform(post("/cleanup/audit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
