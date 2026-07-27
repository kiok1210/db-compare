package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.CompareService;
import com.gcoll.dbcompare.service.DataSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompareController.class)
@AutoConfigureMockMvc(addFilters = false)
class CompareControllerReExecuteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompareService compareService;

    @MockBean
    private DataSourceService dataSourceService;

    @Test
    void reExecute_success_returnsNewTaskId() throws Exception {
        when(compareService.reExecute(12L)).thenReturn(99L);

        mockMvc.perform(post("/compare/reExecute/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(99))
                .andExpect(jsonPath("$.msg").value("任务已重新执行"));
    }

    @Test
    void reExecute_failure_returnsMsg() throws Exception {
        when(compareService.reExecute(5L)).thenThrow(new RuntimeException("任务不存在: 5"));

        mockMvc.perform(post("/compare/reExecute/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("任务不存在: 5"));
    }
}
