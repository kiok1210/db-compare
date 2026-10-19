package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.DataViewerExportService;
import com.gcoll.dbcompare.service.DataViewerService;
import com.gcoll.dbcompare.service.ViewerExportRequest;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(DataViewerController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataViewerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataViewerService dataViewerService;

    @MockBean
    private DataViewerExportService dataViewerExportService;

    @Test
    void page_containsExportControls() throws Exception {
        when(dataViewerService.getAllDataSources()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/viewer"))
                .andExpect(status().isOk())
                .andExpect(view().name("viewer/index"))
                .andExpect(content().string(containsString("导出 SQL")))
                .andExpect(content().string(containsString("onTableContextMenu")))
                .andExpect(content().string(containsString("/viewer/export/")))
                .andExpect(content().string(containsString("暂无数据源")))
                .andExpect(content().string(containsString("去数据源管理添加")));
    }

    @Test
    void dialects_returnsList() throws Exception {
        Map<String, String> mysql = new LinkedHashMap<>();
        mysql.put("id", "MYSQL");
        mysql.put("label", "MySQL");
        when(dataViewerExportService.listDialects()).thenReturn(Collections.singletonList(mysql));
        mockMvc.perform(get("/viewer/dialects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dialects[0].id").value("MYSQL"));
    }

    @Test
    void export_validationFailure_returnsJson() throws Exception {
        when(dataViewerExportService.export(eq(1L), any(ViewerExportRequest.class)))
                .thenThrow(new IllegalArgumentException("请选择要导出的表"));
        mockMvc.perform(post("/viewer/export/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DDL\",\"tables\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("请选择要导出的表"));
    }

    @Test
    void export_success_downloadsSql() throws Exception {
        when(dataViewerExportService.export(eq(1L), any(ViewerExportRequest.class)))
                .thenReturn(new DataViewerExportService.ExportFile("demo_ddl_mysql.sql", "CREATE TABLE t (id INT);\n"));
        mockMvc.perform(post("/viewer/export/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DDL\",\"tables\":[{\"name\":\"t\"}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("demo_ddl_mysql.sql")))
                .andExpect(content().string(containsString("CREATE TABLE t")));
    }
}
