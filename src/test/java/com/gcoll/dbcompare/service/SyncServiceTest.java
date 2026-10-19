package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.repository.SyncTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    private static final long TASK_ID = 88881L;

    @Mock
    private SyncTaskRepository syncTaskRepository;

    @Mock
    private com.gcoll.dbcompare.repository.CompareTaskRepository compareTaskRepository;

    @Mock
    private SyncTaskRunner syncTaskRunner;

    @Mock
    private AppSettingsService appSettingsService;

    private final ReportService reportService = new ReportService();
    private final SyncProgressLogBuffer progressLogBuffer = new SyncProgressLogBuffer();
    private SyncService syncService;

    @BeforeEach
    void setUp() {
        lenient().when(appSettingsService.getSettings()).thenReturn(new AppSettings());
        syncService = new SyncService(syncTaskRepository, compareTaskRepository, syncTaskRunner,
                reportService, appSettingsService, progressLogBuffer);
    }

    @AfterEach
    void cleanup() throws IOException {
        Path dir = Paths.get("reports", "sync", String.valueOf(TASK_ID));
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void executeSync_savesAndDispatchesAsync() {
        SyncTask input = new SyncTask();
        input.setTaskName("sync-a");
        input.setSourceDataSourceId(1L);
        input.setTargetDataSourceId(2L);

        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });

        Long id = syncService.executeSync(input);

        assertEquals(10L, id);
        assertEquals(SyncTask.MODE_FULL_DML, input.getSyncMode());
        verify(syncTaskRunner).run(10L);
    }

    @Test
    void getProgress_includesReportFlagsAndFailedTables() {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_PARTIAL);
        task.setProgress(100);
        task.setSuccessTables(2);
        task.setFailedTablesCount(1);
        task.setFailedTables("[{\"table\":\"db::t1\",\"status\":\"SYNC_FAILED\",\"error\":\"x\"}]");
        task.setReportPath("reports/sync/" + TASK_ID + "/missing.html");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        Map<String, Object> progress = syncService.getProgress(TASK_ID);

        assertEquals(SyncTask.STATUS_PARTIAL, progress.get("status"));
        assertEquals(2, progress.get("successTables"));
        assertEquals(1, progress.get("failedTablesCount"));
        assertFalse((Boolean) progress.get("hasReport"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) progress.get("failedTables");
        assertEquals(1, failed.size());
        assertEquals("db::t1", failed.get(0).get("table"));
    }

    @Test
    void getProgress_includesIncrementalLogs() {
        SyncTask task = baseTask();
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        progressLogBuffer.clear(TASK_ID);
        progressLogBuffer.append(TASK_ID, "info", "start");
        progressLogBuffer.append(TASK_ID, "success", "done");

        Map<String, Object> all = syncService.getProgress(TASK_ID, 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>) all.get("logs");
        assertEquals(2, logs.size());

        Map<String, Object> delta = syncService.getProgress(TASK_ID, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deltaLogs = (List<Map<String, Object>>) delta.get("logs");
        assertEquals(1, deltaLogs.size());
        assertEquals("done", deltaLogs.get(0).get("msg"));
    }

    @Test
    void ensureReport_generatesHtmlWithResyncButton() throws Exception {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_PARTIAL);
        task.setSuccessTables(1);
        task.setFailedTablesCount(1);
        task.setFailedTables("[{\"table\":\"db::fail1\",\"displayName\":\"db.fail1\","
                + "\"status\":\"SYNC_FAILED\",\"statusLabel\":\"同步失败\",\"error\":\"boom\",\"phase\":\"insert\"}]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> inv.getArgument(0));

        String path = syncService.ensureReport(TASK_ID);

        assertTrue(path.endsWith("report.html"));
        assertTrue(Files.exists(Paths.get(path)));
        String html = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"resyncFailedBtn\""));
        assertTrue(html.contains("提取失败表并去确认"));
        assertTrue(html.contains("rpt-header"));
        assertTrue(html.contains("/sync/confirm/"));
        assertTrue(html.contains("db.fail1"));
        assertEquals(path, task.getReportPath());
        verify(syncTaskRepository).save(task);
    }

    @Test
    void ensureReport_reusesExistingReportWithButton() throws Exception {
        Path html = Paths.get("reports", "sync", String.valueOf(TASK_ID), "report.html");
        Files.createDirectories(html.getParent());
        Files.write(html, ("<html><div class=\"rpt-header\"></div>"
                + "<script src=\"/js/confirm-dialog.js\"></script>"
                + "<button id=\"resyncFailedBtn\">ok</button>"
                + "<script>var u='/sync/confirm/1';</script></html>").getBytes(StandardCharsets.UTF_8));

        SyncTask task = baseTask();
        task.setFailedTables("[{\"table\":\"db::t1\"}]");
        task.setReportPath(html.toString());
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        String path = syncService.ensureReport(TASK_ID);

        assertEquals(html.toString().replace('\\', '/'), path.replace('\\', '/'));
        verify(syncTaskRepository, never()).save(any());
    }

    @Test
    void reSyncFailedTables_selectedTables_createsNewTask() {
        SyncTask original = baseTask();
        original.setTaskName("base-sync");
        original.setSourceDataSourceId(1L);
        original.setTargetDataSourceId(2L);
        original.setSyncMode(SyncTask.MODE_FULL_DML);
        original.setBatchSize(5000);
        original.setParallelThreads(4);
        original.setFailedTables("["
                + "{\"table\":\"db::t1\"},"
                + "{\"table\":\"db::t2\"}"
                + "]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(original));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(200L);
            }
            return t;
        });

        Long newId = syncService.reSyncFailedTables(TASK_ID, Collections.singletonList("db::t1"));

        assertEquals(200L, newId);
        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(syncTaskRepository, atLeastOnce()).save(captor.capture());
        SyncTask created = captor.getAllValues().stream()
                .filter(t -> t.getId() != null && t.getId() == 200L)
                .findFirst()
                .orElseThrow(() -> new AssertionError("new task not saved"));
        assertEquals("db::t1", created.getIncludeTables());
        assertTrue(created.getTaskName().contains("一键重同步"));
        assertEquals(Integer.valueOf(4), created.getParallelThreads());
        assertEquals("db", created.getSourceSchema());
        assertTrue(created.getCurrentTable() != null && created.getCurrentTable().startsWith("待确认"));
        verify(syncTaskRunner, never()).run(any());
    }

    @Test
    void reSyncFailedTables_rebuildsSchemaMappingFromTargetList() {
        SyncTask original = baseTask();
        original.setSourceSchema("gcoll_acs,gcoll_aux,other");
        original.setTargetSchema("prd_gcoll_acs,prd_gcoll_aux,prd_other");
        original.setSchemaMapping(null);
        original.setFailedTables("[{\"table\":\"gcoll_acs::t1\"},{\"table\":\"gcoll_aux::t2\"}]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(original));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(202L);
            return t;
        });

        syncService.reSyncFailedTables(TASK_ID, null);

        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(syncTaskRepository, atLeastOnce()).save(captor.capture());
        SyncTask created = captor.getValue();
        assertEquals("gcoll_acs,gcoll_aux", created.getSourceSchema());
        assertTrue(created.getSchemaMapping().contains("gcoll_acs:prd_gcoll_acs"));
        assertTrue(created.getSchemaMapping().contains("gcoll_aux:prd_gcoll_aux"));
        assertEquals("prd_gcoll_acs,prd_gcoll_aux", created.getTargetSchema());
    }

    @Test
    void applySchemaScopeFromTables_preservesExistingMapping() {
        SyncTask task = new SyncTask();
        task.setTargetSchema("prd_a,prd_b");
        task.setSchemaMapping("a:prd_a,b:prd_b,c:prd_c");
        SyncService.applySchemaScopeFromTables(task, Arrays.asList("a::t1", "b::t2"));
        assertEquals("a,b", task.getSourceSchema());
        assertEquals("a:prd_a,b:prd_b", task.getSchemaMapping());
        assertEquals("prd_a,prd_b", task.getTargetSchema());
    }

    @Test
    void reSyncFailedTables_allWhenNull_usesEntireFailedList() {
        SyncTask original = baseTask();
        original.setFailedTables("[{\"table\":\"db::a\"},{\"table\":\"db::b\"}]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(original));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(201L);
            return t;
        });

        syncService.reSyncFailedTables(TASK_ID, null);

        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(syncTaskRepository, atLeastOnce()).save(captor.capture());
        SyncTask created = captor.getValue();
        List<String> tables = Arrays.asList(created.getIncludeTables().split(","));
        assertTrue(tables.contains("db::a"));
        assertTrue(tables.contains("db::b"));
        assertEquals(2, tables.size());
    }

    @Test
    void reSyncFailedTables_unknownTable_throws() {
        SyncTask original = baseTask();
        original.setFailedTables("[{\"table\":\"db::a\"}]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(original));

        assertThrows(RuntimeException.class,
                () -> syncService.reSyncFailedTables(TASK_ID, Collections.singletonList("db::missing")));
        verify(syncTaskRunner, never()).run(any());
    }

    @Test
    void reSyncFailedTables_emptyFailed_throws() {
        SyncTask original = baseTask();
        original.setFailedTables("[]");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(original));

        assertThrows(RuntimeException.class, () -> syncService.reSyncFailedTables(TASK_ID, null));
    }

    @Test
    void createDraft_doesNotStartRunner() {
        SyncTask input = new SyncTask();
        input.setTaskName("draft");
        input.setSourceDataSourceId(1L);
        input.setTargetDataSourceId(2L);
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(77L);
            return t;
        });

        SyncTask draft = syncService.createDraft(input);

        assertEquals(77L, draft.getId());
        assertEquals(SyncTask.STATUS_PENDING, draft.getStatus());
        verify(syncTaskRunner, never()).run(any());
    }

    @Test
    void createDraft_defaultsParallelToTableCountCappedAt20() {
        SyncTask input = new SyncTask();
        input.setTaskName("draft-many");
        input.setSourceDataSourceId(1L);
        input.setTargetDataSourceId(2L);
        StringBuilder tables = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) tables.append(',');
            tables.append("s::t").append(i);
        }
        input.setIncludeTables(tables.toString());
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(79L);
            return t;
        });

        SyncTask draft = syncService.createDraft(input);

        assertEquals(20, draft.getParallelThreads().intValue());
    }

    @Test
    void startDraft_runsPendingTask() {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_PENDING);
        task.setStartTime(null);
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        Long id = syncService.startDraft(TASK_ID);

        assertEquals(TASK_ID, id);
        verify(syncTaskRunner).run(TASK_ID);
    }

    @Test
    void createDraftFromCompareCountDiff_copiesSchemasAndTables() {
        com.gcoll.dbcompare.entity.CompareTask compare = new com.gcoll.dbcompare.entity.CompareTask();
        compare.setId(5L);
        compare.setTaskName("cmp");
        compare.setSourceDataSourceId(1L);
        compare.setTargetDataSourceId(2L);
        compare.setSourceSchema("a,b");
        compare.setTargetSchema("prd_a,prd_b");
        compare.setSchemaMapping("a:prd_a,b:prd_b");
        when(compareTaskRepository.findById(5L)).thenReturn(Optional.of(compare));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> {
            SyncTask t = inv.getArgument(0);
            t.setId(88L);
            return t;
        });

        SyncTask draft = syncService.createDraftFromCompareCountDiff(5L,
                Arrays.asList("a::t1", "b::t2", " a::t1 "), SyncTask.MODE_FULL_DML);

        assertEquals(88L, draft.getId());
        assertEquals(SyncTask.STATUS_PENDING, draft.getStatus());
        assertEquals("a::t1,b::t2", draft.getIncludeTables());
        assertEquals(2, draft.getParallelThreads().intValue());
        assertEquals("a:prd_a,b:prd_b", draft.getSchemaMapping());
        assertTrue(draft.getTaskName().contains("条数差异同步"));
        assertEquals("a,b", draft.getSourceSchema());
        verify(syncTaskRunner, never()).run(any());
        assertTrue(syncService.isAwaitingManualStart(draft));
    }

    @Test
    void updateDraft_updatesDataxClearMode() {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_PENDING);
        task.setStartTime(null);
        task.setCurrentTable("待确认：x");
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncTask patch = new SyncTask();
        patch.setDataxClearMode("PARALLEL_DELETE");
        patch.setClearParallelThreads(6);
        SyncTask saved = syncService.updateDraft(TASK_ID, patch);

        assertEquals("PARALLEL_DELETE", saved.getDataxClearMode());
        assertEquals(6, saved.getClearParallelThreads());
    }


    @Test
    void schemasFromTableKeys_extractsSchemas() {
        assertEquals("a,b", SyncService.schemasFromTableKeys(Arrays.asList("a::t1", "b::t2", "a::t3")));
    }

    @Test
    void isAwaitingManualStart_falseForImmediateExecuteDraft() {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_PENDING);
        task.setStartTime(null);
        task.setCurrentTable(null);
        assertFalse(syncService.isAwaitingManualStart(task));
    }

    @Test
    void cancelTask_running_requestsCancel() {
        SyncTask task = baseTask();
        task.setStatus(SyncTask.STATUS_RUNNING);
        when(syncTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(syncTaskRepository.save(any(SyncTask.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(syncService.cancelTask(TASK_ID));
        verify(syncTaskRunner).requestCancel(TASK_ID);
        assertEquals(SyncTask.STATUS_CANCELLED, task.getStatus());
    }

    private SyncTask baseTask() {
        SyncTask task = new SyncTask();
        task.setId(TASK_ID);
        task.setTaskName("unit-sync");
        task.setSourceDataSourceId(1L);
        task.setTargetDataSourceId(2L);
        task.setSyncMode(SyncTask.MODE_FULL_DML);
        return task;
    }
}
