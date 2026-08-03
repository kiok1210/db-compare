package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.AppMeta;
import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.AppMetaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    @Mock
    private AppMetaRepository appMetaRepository;

    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingsService(appMetaRepository);
    }

    @Test
    void getSettings_missing_returnsDefaults() {
        when(appMetaRepository.findById(AppSettingsService.KEY_APP_SETTINGS)).thenReturn(Optional.empty());
        AppSettings s = service.getSettings();
        assertEquals(8, s.getSyncParallelThreads());
        assertEquals(20000, s.getSyncBatchSize());
        assertEquals(SyncTask.MODE_FULL_DML, s.getSyncDefaultMode());
        assertEquals(1000, s.getCompareBatchSize());
        assertEquals("insert", s.getDataxWriteMode());
        assertEquals("AUTO", s.getDataxClearMode());
        assertEquals(4, s.getDataxChannel());
        assertEquals(1, s.getDataxMaxConcurrent());
    }

    @Test
    void saveSettings_clampsAndPersists() {
        when(appMetaRepository.findById(AppSettingsService.KEY_APP_SETTINGS)).thenReturn(Optional.empty());
        when(appMetaRepository.save(any(AppMeta.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings patch = new AppSettings();
        patch.setSyncParallelThreads(99);
        patch.setSyncBatchSize(50);
        patch.setSyncDeleteBatchSize(100);
        patch.setCompareBatchSize(10);
        patch.setSyncDefaultMode("FULL_DDL");

        AppSettings saved = service.saveSettings(patch);
        assertEquals(20, saved.getSyncParallelThreads());
        assertEquals(20000, saved.getSyncBatchSize());
        assertEquals(50000, saved.getSyncDeleteBatchSize());
        assertEquals(1000, saved.getCompareBatchSize());
        assertEquals(SyncTask.MODE_FULL_DDL, saved.getSyncDefaultMode());

        ArgumentCaptor<AppMeta> cap = ArgumentCaptor.forClass(AppMeta.class);
        verify(appMetaRepository).save(cap.capture());
        assertEquals(AppSettingsService.KEY_APP_SETTINGS, cap.getValue().getMetaKey());
    }

    @Test
    void normalize_validValuesKept() {
        AppSettings in = new AppSettings();
        in.setSyncParallelThreads(4);
        in.setSyncBatchSize(10000);
        in.setSyncDeleteBatchSize(30000);
        in.setCompareBatchSize(500);
        in.setDataxWriteMode("REPLACE");
        AppSettings out = AppSettingsService.normalize(in);
        assertEquals(4, out.getSyncParallelThreads());
        assertEquals(10000, out.getSyncBatchSize());
        assertEquals(30000, out.getSyncDeleteBatchSize());
        assertEquals(500, out.getCompareBatchSize());
        assertEquals("replace", out.getDataxWriteMode());
    }

    @Test
    void normalize_dataxConcurrencyClamped() {
        AppSettings in = new AppSettings();
        in.setDataxChannel(99);
        in.setDataxMaxConcurrent(9);
        AppSettings out = AppSettingsService.normalize(in);
        assertEquals(16, out.getDataxChannel());
        assertEquals(3, out.getDataxMaxConcurrent());
    }
}
