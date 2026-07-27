package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.service.AppSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppSettingsService appSettingsService;

    @Test
    void page_returnsSettingsView() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/index"));
    }

    @Test
    void page_containsDdlDmlModeCards() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DATA_SYNC")))
                .andExpect(content().string(containsString("差异同步")))
                .andExpect(content().string(containsString("增量同步")))
                .andExpect(content().string(containsString("默认同步模式")));
    }

    @Test
    void api_post_savesSyncDefaultMode() throws Exception {
        AppSettings saved = new AppSettings();
        saved.setSyncDefaultMode("FULL_DDL");
        when(appSettingsService.saveSettings(any(AppSettings.class))).thenReturn(saved);
        mockMvc.perform(post("/settings/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncDefaultMode\":\"FULL_DDL\",\"syncParallelThreads\":8,\"syncBatchSize\":20000,\"syncDeleteBatchSize\":50000,\"compareBatchSize\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.settings.syncDefaultMode").value("FULL_DDL"));
    }

    @Test
    void api_get_returnsSettings() throws Exception {
        AppSettings s = new AppSettings();
        s.setSyncParallelThreads(6);
        when(appSettingsService.getSettings()).thenReturn(s);
        mockMvc.perform(get("/settings/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.settings.syncParallelThreads").value(6));
    }

    @Test
    void api_post_saves() throws Exception {
        AppSettings saved = new AppSettings();
        saved.setSyncParallelThreads(4);
        when(appSettingsService.saveSettings(any(AppSettings.class))).thenReturn(saved);
        mockMvc.perform(post("/settings/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncParallelThreads\":4,\"syncBatchSize\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.settings.syncParallelThreads").value(4));
    }
}
