package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.CompareService;
import com.gcoll.dbcompare.service.DataSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompareController.class)
@AutoConfigureMockMvc(addFilters = false)
class CompareControllerRowDiffTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompareService compareService;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    void rowDiff_success_returnsData() throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("table", "gcoll_acs::logrecord");
        detail.put("sourceCount", 10);
        detail.put("targetCount", 9);
        detail.put("onlyInSourceCount", 1);
        detail.put("onlyInTargetCount", 0);
        detail.put("diffRows", Collections.singletonList(Collections.singletonMap("type", "only_in_source")));
        when(compareService.findRowDiffDetails(eq(42L), eq("gcoll_acs::logrecord"), isNull()))
                .thenReturn(detail);

        mockMvc.perform(get("/compare/row-diff/42")
                        .param("table", "gcoll_acs::logrecord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.table").value("gcoll_acs::logrecord"))
                .andExpect(jsonPath("$.data.onlyInSourceCount").value(1));
    }

    @Test
    void rowDiff_blankTable_returnsFailure() throws Exception {
        when(compareService.findRowDiffDetails(eq(7L), eq(" "), eq(50)))
                .thenThrow(new IllegalArgumentException("请指定表名"));

        mockMvc.perform(get("/compare/row-diff/7")
                        .param("table", " ")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("请指定表名"));
    }

    @Test
    void rowDiff_serviceError_returnsFailureMsg() throws Exception {
        when(compareService.findRowDiffDetails(eq(3L), eq("t1"), eq(100)))
                .thenThrow(new RuntimeException("无主键，无法定位差异行"));

        mockMvc.perform(get("/compare/row-diff/3")
                        .param("table", "t1")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("无主键，无法定位差异行"));
    }
}
