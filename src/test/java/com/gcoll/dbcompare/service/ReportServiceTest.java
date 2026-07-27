package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.SyncTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    private final ReportService reportService = new ReportService();

    @AfterEach
    void cleanup() throws IOException {
        deleteTree(Paths.get("reports", "99999"));
        deleteTree(Paths.get("reports", "sync", "88888"));
    }

    private void deleteTree(Path reportsDir) throws IOException {
        if (Files.exists(reportsDir)) {
            Files.walk(reportsDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
        }
    }

    @Test
    void generateReport_createsHtmlAndMarkdown() throws IOException {
        CompareTask task = buildTask();
        Map<String, Object> compareResult = buildCompareResult();

        String reportPath = reportService.generateReport(task, compareResult);

        assertTrue(reportPath.endsWith("report.html"));
        assertTrue(Files.exists(Paths.get(reportPath)));
        assertTrue(Files.exists(Paths.get(reportPath).getParent().resolve("report.md")));

        String html = new String(Files.readAllBytes(Paths.get(reportPath)), StandardCharsets.UTF_8);
        assertTrue(html.contains("数据库比对报告"));
        assertTrue(html.contains("user_info"));
        assertTrue(html.contains("extractCountDiffSync"));
        assertTrue(html.contains("compareTaskId = 99999"));
        assertTrue(html.contains("提取为同步任务"));
        assertTrue(html.contains("btnReExecuteCompare") || html.contains("btn-reexec"));
        assertTrue(html.contains("重新执行"));
        assertTrue(html.contains("data-dbcompare-nav=\"1\""));
        assertTrue(html.contains("数据库比对工具"));
        assertTrue(html.contains("data-dbcompare-extract-force=\"1\"")
                || html.contains("extractCountDiffSync"));
        assertTrue(html.contains("data-dbcompare-reexec=\"1\"") || html.contains("/compare/reExecute/"));
    }

    @Test
    void loadReport_returnsMetadata() throws IOException {
        CompareTask task = buildTask();
        Path reportFile = Paths.get("reports", "99999", "report.html");
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, "<html>test</html>".getBytes());
        task.setReportPath(reportFile.toString());

        Map<String, Object> meta = reportService.loadReport(task);

        assertEquals(99999L, meta.get("taskId"));
        assertEquals("SUCCESS", meta.get("status"));
        assertNotNull(meta.get("htmlUrl"));
        assertTrue(((String) meta.get("htmlUrl")).contains("report.html"));
        assertEquals(true, meta.get("htmlExists"));
    }

    private CompareTask buildTask() {
        CompareTask task = new CompareTask();
        task.setId(99999L);
        task.setTaskName("unit-test-task");
        task.setCompareMode("FULL");
        task.setStatus("SUCCESS");
        task.setTotalTables(1);
        task.setStartTime(LocalDateTime.now().minusMinutes(1));
        task.setEndTime(LocalDateTime.now());
        return task;
    }

    private Map<String, Object> buildCompareResult() {
        Map<String, Object> tableResult = new HashMap<>();
        tableResult.put("consistent", true);
        tableResult.put("schema", "一致");

        Map<String, Object> count = new HashMap<>();
        count.put("sourceCount", 100L);
        count.put("targetCount", 100L);
        count.put("diff", 0L);
        count.put("consistent", true);
        tableResult.put("count", count);
        tableResult.put("amount", "无金额字段");

        Map<String, Object> hash = new HashMap<>();
        hash.put("consistent", true);
        tableResult.put("rowHash", hash);

        Map<String, Object> result = new HashMap<>();
        result.put("user_info", tableResult);
        return result;
    }

    @Test
    void generateSyncReport_withFailedTables_includesResyncControls() throws IOException {
        SyncTask task = buildSyncTask();
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("table", "src::orders");
        row.put("displayName", "src.orders");
        row.put("tableIndex", 12);
        row.put("status", "SYNC_FAILED");
        row.put("statusLabel", "同步失败");
        row.put("phase", "insert");
        row.put("error", "wait_timeout");
        row.put("rowCount", 0);
        failed.add(row);

        Map<String, Object> ok = new HashMap<>();
        ok.put("table", "src::users");
        ok.put("displayName", "src.users");
        ok.put("tableIndex", 1);
        ok.put("rowCount", 100);

        String reportPath = reportService.generateSyncReport(
                task, Collections.singletonList(ok), failed);

        assertTrue(reportPath.replace('\\', '/').endsWith("reports/sync/88888/report.html"));
        assertTrue(Files.exists(Paths.get(reportPath)));
        assertTrue(Files.exists(Paths.get(reportPath).getParent().resolve("report.md")));

        String html = new String(Files.readAllBytes(Paths.get(reportPath)), StandardCharsets.UTF_8);
        assertTrue(html.contains("同步报告"));
        assertTrue(html.contains("id=\"resyncFailedBtn\""));
        assertTrue(html.contains("var TASK_ID=88888"));
        assertTrue(html.contains("/sync/resync-failed/"));
        assertTrue(html.contains("confirmUrl") || html.contains("/sync/confirm/"));
        assertTrue(html.contains("rpt-header"));
        assertTrue(html.contains("数据库比对工具"));
        assertTrue(html.contains("src.orders"));
        assertTrue(html.contains("src.users"));
        assertTrue(html.contains("value=\"src::orders\""));
    }

    @Test
    void generateSyncReport_noFailed_hidesResyncButton() throws IOException {
        SyncTask task = buildSyncTask();
        task.setStatus(SyncTask.STATUS_SUCCESS);
        task.setFailedTablesCount(0);

        String reportPath = reportService.generateSyncReport(
                task, Collections.emptyList(), Collections.emptyList());

        String html = new String(Files.readAllBytes(Paths.get(reportPath)), StandardCharsets.UTF_8);
        assertFalse(html.contains("id=\"resyncFailedBtn\""));
        assertTrue(html.contains("无待重同步表"));
    }

    private SyncTask buildSyncTask() {
        SyncTask task = new SyncTask();
        task.setId(88888L);
        task.setTaskName("sync-unit-report");
        task.setStatus(SyncTask.STATUS_PARTIAL);
        task.setSyncMode(SyncTask.MODE_FULL_DML);
        task.setParallelThreads(4);
        task.setSourceSchema("src");
        task.setTargetSchema("tgt");
        task.setSuccessTables(1);
        task.setFailedTablesCount(1);
        task.setTotalTables(2);
        task.setStartTime(LocalDateTime.now().minusMinutes(5));
        task.setEndTime(LocalDateTime.now());
        return task;
    }
}
