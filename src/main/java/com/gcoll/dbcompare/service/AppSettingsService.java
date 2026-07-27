package com.gcoll.dbcompare.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcoll.dbcompare.entity.AppMeta;
import com.gcoll.dbcompare.entity.AppSettings;
import com.gcoll.dbcompare.entity.SyncTask;
import com.gcoll.dbcompare.repository.AppMetaRepository;
import com.gcoll.dbcompare.service.datax.DataxClearMode;
import com.gcoll.dbcompare.service.datax.DataxWriteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 全局参数：读写 AppMeta，供新建同步/比对任务默认值。
 */
@Service
public class AppSettingsService {

    public static final String KEY_APP_SETTINGS = "APP_SETTINGS";

    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    private final AppMetaRepository appMetaRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile AppSettings cached;

    public AppSettingsService(AppMetaRepository appMetaRepository) {
        this.appMetaRepository = appMetaRepository;
    }

    public AppSettings getSettings() {
        AppSettings c = cached;
        if (c != null) {
            return copy(c);
        }
        AppSettings loaded = loadFromDb();
        cached = loaded;
        return copy(loaded);
    }

    public AppSettings saveSettings(AppSettings patch) {
        AppSettings normalized = normalize(patch != null ? patch : new AppSettings());
        try {
            String json = objectMapper.writeValueAsString(normalized);
            AppMeta meta = appMetaRepository.findById(KEY_APP_SETTINGS).orElseGet(AppMeta::new);
            meta.setMetaKey(KEY_APP_SETTINGS);
            meta.setMetaValue(json);
            meta.setUpdateTime(LocalDateTime.now());
            appMetaRepository.save(meta);
            cached = normalized;
            log.info("已保存全局参数: parallel={}, syncBatch={}, deleteBatch={}, mode={}, compareBatch={}, dataxHome={}, dataxChannel={}, dataxWriteMode={}",
                    normalized.getSyncParallelThreads(), normalized.getSyncBatchSize(),
                    normalized.getSyncDeleteBatchSize(), normalized.getSyncDefaultMode(),
                    normalized.getCompareBatchSize(),
                    normalized.getDataxHome() == null || normalized.getDataxHome().isEmpty() ? "(空)" : normalized.getDataxHome(),
                    normalized.getDataxChannel(),
                    normalized.getDataxWriteMode());
            return copy(normalized);
        } catch (Exception e) {
            throw new RuntimeException("保存全局参数失败: " + e.getMessage(), e);
        }
    }

    public AppSettings resetToDefaults() {
        return saveSettings(new AppSettings());
    }

    /** 规范化并校验范围 */
    public static AppSettings normalize(AppSettings in) {
        AppSettings s = in != null ? in : new AppSettings();
        AppSettings out = new AppSettings();
        out.setSyncParallelThreads(SyncTask.clampParallelThreads(s.getSyncParallelThreads()));
        out.setSyncBatchSize(clamp(s.getSyncBatchSize(), 1000, 100000, 20000));
        out.setSyncDeleteBatchSize(clamp(s.getSyncDeleteBatchSize(), 5000, 200000, 50000));
        out.setSyncDefaultMode(SyncTask.normalizeMode(s.getSyncDefaultMode()));
        out.setCompareBatchSize(clamp(s.getCompareBatchSize(), 100, 20000, 1000));
        out.setDataxHome(s.getDataxHome() != null ? s.getDataxHome().trim() : "");
        out.setDataxChannel(clamp(s.getDataxChannel(), 1, 32, 8));
        out.setDataxClearMode(DataxClearMode.normalize(s.getDataxClearMode()));
        out.setDataxWriteMode(DataxWriteMode.normalize(s.getDataxWriteMode()));
        return out;
    }

    private AppSettings loadFromDb() {
        try {
            Optional<AppMeta> opt = appMetaRepository.findById(KEY_APP_SETTINGS);
            if (opt.isPresent() && opt.get().getMetaValue() != null && !opt.get().getMetaValue().trim().isEmpty()) {
                AppSettings parsed = objectMapper.readValue(opt.get().getMetaValue(), AppSettings.class);
                return normalize(parsed);
            }
        } catch (Exception e) {
            log.warn("读取全局参数失败，使用默认值: {}", e.getMessage());
        }
        return normalize(new AppSettings());
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v < min || v > max) {
            return def;
        }
        return v;
    }

    private static AppSettings copy(AppSettings s) {
        AppSettings c = new AppSettings();
        c.setSyncParallelThreads(s.getSyncParallelThreads());
        c.setSyncBatchSize(s.getSyncBatchSize());
        c.setSyncDeleteBatchSize(s.getSyncDeleteBatchSize());
        c.setSyncDefaultMode(s.getSyncDefaultMode());
        c.setCompareBatchSize(s.getCompareBatchSize());
        c.setDataxHome(s.getDataxHome());
        c.setDataxChannel(s.getDataxChannel());
        c.setDataxClearMode(s.getDataxClearMode());
        c.setDataxWriteMode(s.getDataxWriteMode());
        return c;
    }
}
