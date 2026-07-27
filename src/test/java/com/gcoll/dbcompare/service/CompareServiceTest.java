package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompareServiceTest {

    @Mock
    private CompareTaskRepository taskRepository;

    @Mock
    private CompareTaskRunner compareTaskRunner;

    @Mock
    private ReportService reportService;

    @Mock
    private DataSourceService dataSourceService;

    @InjectMocks
    private CompareService compareService;

    @Test
    void executeCompare_savesAndDispatchesAsync() {
        CompareTask input = new CompareTask();
        input.setTaskName("test-task");

        CompareTask saved = new CompareTask();
        saved.setId(42L);
        when(taskRepository.save(any(CompareTask.class))).thenReturn(saved);

        Long taskId = compareService.executeCompare(input);

        assertEquals(42L, taskId);
        verify(compareTaskRunner).run(42L);
    }

    @Test
    void getTaskById_notFound_throws() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> compareService.getTaskById(99L));
    }

    @Test
    void getProgress_returnsTaskFields() {
        CompareTask task = new CompareTask();
        task.setStatus("RUNNING");
        task.setProgress(50);
        task.setCurrentTable("user_info");
        task.setProcessedTables(5);
        task.setTotalTables(10);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        Map<String, Object> progress = compareService.getProgress(1L);

        assertEquals("RUNNING", progress.get("status"));
        assertEquals(50, progress.get("progress"));
        assertEquals("user_info", progress.get("currentTable"));
    }

    @Test
    void reExecute_usesOptimizedNameWithoutStackedSuffix() {
        CompareTask original = new CompareTask();
        original.setId(10L);
        original.setTaskName("生产环境信创比对-0721 - 重新执行 - 重新执行 - 重新执行");
        original.setSourceDataSourceId(1L);
        original.setTargetDataSourceId(2L);
        original.setCompareMode("FULL");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(original));
        when(taskRepository.save(any(CompareTask.class))).thenAnswer(inv -> {
            CompareTask t = inv.getArgument(0);
            t.setId(11L);
            return t;
        });

        Long newId = compareService.reExecute(10L);

        assertEquals(11L, newId);
        ArgumentCaptor<CompareTask> captor = ArgumentCaptor.forClass(CompareTask.class);
        verify(taskRepository).save(captor.capture());
        String name = captor.getValue().getTaskName();
        assertTrue(name.startsWith("生产环境信创比对-0721 - 重新执行 "));
        assertFalse(name.contains("重新执行 - 重新执行"));
        verify(compareTaskRunner).run(11L);
    }
}
