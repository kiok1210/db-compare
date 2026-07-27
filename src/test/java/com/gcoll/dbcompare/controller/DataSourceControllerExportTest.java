package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.DataSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataSourceController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataSourceControllerExportTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    void exportSql_returnsAttachment() throws Exception {
        when(dataSourceService.exportDataSourceSql())
                .thenReturn("-- full\nMERGE INTO dbcompare_datasource (id) KEY(id) VALUES (1);\n");

        mockMvc.perform(get("/datasource/export.sql"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("dbcompare_datasource_full.sql")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MERGE INTO")));
    }
}
