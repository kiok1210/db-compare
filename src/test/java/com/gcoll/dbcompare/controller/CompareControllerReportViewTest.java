package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.service.CompareService;
import com.gcoll.dbcompare.service.DataSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompareController.class)
@AutoConfigureMockMvc(addFilters = false)
class CompareControllerReportViewTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompareService compareService;

    @MockBean
    private DataSourceService dataSourceService;

    @TempDir
    Path tempDir;

    @Test
    void viewReport_successWithFile_writesEnhancedHtmlWithNav() throws Exception {
        Path report = tempDir.resolve("report.html");
        Files.write(report, ("<!DOCTYPE html><html><body><h1>rpt</h1>"
                + "<script>function openDimModal(t){}</script></body></html>").getBytes(StandardCharsets.UTF_8));

        CompareTask task = new CompareTask();
        task.setId(42L);
        task.setTaskName("cmp");
        task.setStatus("SUCCESS");
        task.setReportPath(report.toString());
        when(compareService.getTaskById(42L)).thenReturn(task);

        MvcResult result = mockMvc.perform(get("/compare/report/42"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("data-dbcompare-nav=\"1\""));
        assertTrue(body.contains("数据库比对工具"));
        assertTrue(body.contains("data-dbcompare-extract-force=\"1\"")
                || body.contains("extractCountDiffSync"));
        assertTrue(body.contains("COMPARE_TASK_ID = 42") || body.contains("rpt"));
        assertTrue(body.contains("data-dbcompare-reexec=\"1\"") || body.contains("/compare/reExecute/"));
        assertTrue(body.contains("重新执行"));
    }
}
