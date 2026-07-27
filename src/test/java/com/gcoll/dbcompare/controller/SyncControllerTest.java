package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.service.DataSourceService;
import com.gcoll.dbcompare.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SyncService syncService;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    void execute_returnsTaskId() throws Exception {
        when(syncService.executeSync(any())).thenReturn(55L);

        mockMvc.perform(post("/sync/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\":\"t\",\"sourceDataSourceId\":1,\"targetDataSourceId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(55));
    }

    @Test
    void progressData_returnsPayload() throws Exception {
        Map<String, Object> progress = new HashMap<>();
        progress.put("status", "SUCCESS");
        progress.put("progress", 100);
        progress.put("hasReport", true);
        when(syncService.getProgress(eq(7L), anyLong())).thenReturn(progress);

        mockMvc.perform(get("/sync/progress/7/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.hasReport").value(true));

        mockMvc.perform(get("/sync/progress/7/data").param("sinceLogSeq", "5"))
                .andExpect(status().isOk());
        verify(syncService).getProgress(7L, 5L);
    }

    @Test
    void viewReport_redirectsToGeneratedHtml() throws Exception {
        when(syncService.ensureReport(9L)).thenReturn("reports/sync/9/report.html");
        // ensure file exists for redirect branch
        java.nio.file.Path p = java.nio.file.Paths.get("reports/sync/9/report.html");
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.write(p, "<html></html>".getBytes());

        try {
            mockMvc.perform(get("/sync/report/9"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reports/sync/9/report.html"));
        } finally {
            java.nio.file.Files.deleteIfExists(p);
        }
    }

    @Test
    void resyncFailed_withTables_startsNewTask() throws Exception {
        when(syncService.reSyncFailedTables(eq(3L), anyList())).thenReturn(99L);

        mockMvc.perform(post("/sync/resync-failed/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tables\":[\"db::t1\",\"db::t2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(99))
                .andExpect(jsonPath("$.confirmUrl").value("/sync/confirm/99"));

        verify(syncService).reSyncFailedTables(eq(3L), eq(Arrays.asList("db::t1", "db::t2")));
    }

    @Test
    void resyncFailed_emptyBody_usesFailedFromProgress() throws Exception {
        Map<String, Object> progress = new HashMap<>();
        Map<String, Object> row = new HashMap<>();
        row.put("table", "db::x");
        progress.put("failedTables", Collections.singletonList(row));
        when(syncService.getProgress(4L)).thenReturn(progress);
        when(syncService.reSyncFailedTables(eq(4L), anyList())).thenReturn(101L);

        mockMvc.perform(post("/sync/resync-failed/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(101));

        verify(syncService).reSyncFailedTables(eq(4L), eq(Collections.singletonList("db::x")));
    }

    @Test
    void resyncFailed_serviceError_returnsFailure() throws Exception {
        when(syncService.reSyncFailedTables(eq(5L), isNull()))
                .thenThrow(new RuntimeException("该任务没有可重同步的表"));
        // empty tables + empty progress failed list → controller calls with empty then service...
        // Actually controller builds tables from body/progress; empty → reSyncFailedTables(5, [])
        Map<String, Object> progress = new HashMap<>();
        progress.put("failedTables", Collections.emptyList());
        when(syncService.getProgress(5L)).thenReturn(progress);
        when(syncService.reSyncFailedTables(eq(5L), eq(Collections.emptyList())))
                .thenThrow(new RuntimeException("该任务没有可重同步的表"));

        mockMvc.perform(post("/sync/resync-failed/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("该任务没有可重同步的表"));
    }

    @Test
    void fromCompare_createsDraftAndReturnsConfirmUrl() throws Exception {
        SyncTask draft = new SyncTask();
        draft.setId(66L);
        draft.setStatus(SyncTask.STATUS_PENDING);
        when(syncService.createDraftFromCompareCountDiff(eq(12L), anyList(), eq("FULL_DML")))
                .thenReturn(draft);

        mockMvc.perform(post("/sync/from-compare/12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tables\":[\"a::t1\",\"b::t2\"],\"syncMode\":\"FULL_DML\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(66))
                .andExpect(jsonPath("$.confirmUrl").value("/sync/confirm/66"));

        verify(syncService).createDraftFromCompareCountDiff(eq(12L),
                eq(Arrays.asList("a::t1", "b::t2")), eq("FULL_DML"));
    }

    @Test
    void startDraft_returnsTaskId() throws Exception {
        when(syncService.startDraft(66L)).thenReturn(66L);

        mockMvc.perform(post("/sync/start/66"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(66));

        verify(syncService).startDraft(66L);
    }

    @Test
    void confirmPage_renders() throws Exception {
        SyncTask task = new SyncTask();
        task.setId(66L);
        task.setTaskName("draft");
        task.setStatus(SyncTask.STATUS_PENDING);
        task.setIncludeTables("a::t1,b::t2");
        task.setSyncMode(SyncTask.MODE_FULL_DML);
        when(syncService.getTaskById(66L)).thenReturn(task);
        when(dataSourceService.getAllDataSources()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/sync/confirm/66"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("差异同步")))
                .andExpect(content().string(containsString("增量同步")))
                .andExpect(content().string(containsString("DATA_SYNC")));
    }

    @Test
    void updateDraft_returnsSuccess() throws Exception {
        SyncTask saved = new SyncTask();
        saved.setId(66L);
        saved.setIncludeTables("a::t1");
        when(syncService.updateDraft(eq(66L), any())).thenReturn(saved);

        mockMvc.perform(post("/sync/draft/66")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeTables\":\"a::t1\",\"sourceSchema\":\"a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(66));
    }
}
