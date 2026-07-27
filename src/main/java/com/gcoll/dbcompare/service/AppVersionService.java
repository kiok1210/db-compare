package com.gcoll.dbcompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcoll.dbcompare.config.SchemaScriptMigrator;
import com.gcoll.dbcompare.entity.AppMeta;
import com.gcoll.dbcompare.repository.AppMetaRepository;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 应用版本与更新日志；检测数据库 schema 是否在本次启动中升级。
 * 结构变更由 {@link SchemaScriptMigrator} 执行 classpath 增量 SQL。
 */
@Service
@Order(50)
public class AppVersionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

    private final AppMetaRepository appMetaRepository;
    private final DatasourceConfigRepository DatasourceConfigRepository;
    private final SchemaScriptMigrator schemaScriptMigrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> changelogRoot = new LinkedHashMap<>();
    private volatile boolean dbUpdatedThisStartup = false;
    private volatile int previousSchemaVersion = 0;
    private volatile int currentSchemaVersion = 1;
    private volatile List<String> appliedDbChanges = new ArrayList<>();

    public AppVersionService(AppMetaRepository appMetaRepository,
                             DatasourceConfigRepository DatasourceConfigRepository,
                             SchemaScriptMigrator schemaScriptMigrator) {
        this.appMetaRepository = appMetaRepository;
        this.DatasourceConfigRepository = DatasourceConfigRepository;
        this.schemaScriptMigrator = schemaScriptMigrator;
        loadChangelog();
    }

    @Override
    public void run(ApplicationArguments args) {
        checkAndRecordSchemaVersion();
    }

    private void loadChangelog() {
        try (InputStream in = new ClassPathResource("version-changelog.json").getInputStream()) {
            changelogRoot = objectMapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            Object sv = changelogRoot.get("schemaVersion");
            if (sv instanceof Number) {
                currentSchemaVersion = ((Number) sv).intValue();
            }
            log.info("已加载版本信息: app={}, schema={}", getAppVersion(), currentSchemaVersion);
        } catch (Exception e) {
            log.error("加载 version-changelog.json 失败，使用默认版本 1.0.0", e);
            changelogRoot = defaultChangelog();
            currentSchemaVersion = 1;
        }
    }

    private void checkAndRecordSchemaVersion() {
        try {
            int oldVersion = readStoredSchemaVersion();
            previousSchemaVersion = oldVersion;

            boolean existingInstall = false;
            try {
                existingInstall = DatasourceConfigRepository.count() > 0;
            } catch (Exception ignored) {
            }
            // 旧库无 meta：有数据源则视为从 schema 1 升级
            if (oldVersion == 0 && existingInstall && currentSchemaVersion > 1) {
                previousSchemaVersion = 1;
                oldVersion = 1;
            }
            boolean freshInstall = previousSchemaVersion == 0 && !existingInstall;

            List<Integer> appliedScripts = schemaScriptMigrator.apply(oldVersion, currentSchemaVersion);
            int afterScripts = readStoredSchemaVersion();
            if (afterScripts < currentSchemaVersion) {
                saveMeta(AppMeta.KEY_SCHEMA_VERSION, String.valueOf(currentSchemaVersion));
            }

            if (freshInstall) {
                dbUpdatedThisStartup = false;
                appliedDbChanges = Collections.emptyList();
                log.info("首次写入 schema 版本: {}（已执行增量脚本 {} 个）",
                        currentSchemaVersion, appliedScripts.size());
            } else if (previousSchemaVersion < currentSchemaVersion) {
                dbUpdatedThisStartup = true;
                appliedDbChanges = collectDbChangesBeyond(previousSchemaVersion);
                log.info("检测到数据库结构升级: schema {} → {}，脚本 {} 个，变更说明 {} 条",
                        previousSchemaVersion, currentSchemaVersion,
                        appliedScripts.size(), appliedDbChanges.size());
            } else {
                dbUpdatedThisStartup = false;
                appliedDbChanges = Collections.emptyList();
            }
            saveMeta(AppMeta.KEY_LAST_APP_VERSION, getAppVersion());
        } catch (Exception e) {
            log.warn("检查 schema 版本失败: {}", e.getMessage());
        }
    }

    private int readStoredSchemaVersion() {
        Optional<AppMeta> existing = appMetaRepository.findById(AppMeta.KEY_SCHEMA_VERSION);
        if (existing.isPresent() && existing.get().getMetaValue() != null) {
            try {
                return Integer.parseInt(existing.get().getMetaValue().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> collectDbChangesBeyond(int fromSchemaExclusive) {
        List<String> changes = new ArrayList<>();
        Object releasesObj = changelogRoot.get("releases");
        if (!(releasesObj instanceof List)) {
            return changes;
        }
        for (Object item : (List<?>) releasesObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> rel = (Map<String, Object>) item;
            Object sv = rel.get("schemaVersion");
            int relSchema = sv instanceof Number ? ((Number) sv).intValue() : 0;
            if (relSchema > fromSchemaExclusive) {
                Object db = rel.get("dbChanges");
                if (db instanceof List) {
                    for (Object line : (List<?>) db) {
                        if (line != null && !String.valueOf(line).trim().isEmpty()) {
                            changes.add(String.valueOf(line));
                        }
                    }
                }
            }
        }
        return changes;
    }

    private void saveMeta(String key, String value) {
        AppMeta meta = appMetaRepository.findById(key).orElseGet(AppMeta::new);
        meta.setMetaKey(key);
        meta.setMetaValue(value);
        meta.setUpdateTime(LocalDateTime.now());
        appMetaRepository.save(meta);
    }

    public String getAppVersion() {
        Object v = changelogRoot.get("version");
        return v != null ? String.valueOf(v) : "1.0.0";
    }

    public String getFrontendVersion() {
        Object v = changelogRoot.get("frontendVersion");
        return v != null ? String.valueOf(v) : getAppVersion();
    }

    public String getBackendVersion() {
        Object v = changelogRoot.get("backendVersion");
        return v != null ? String.valueOf(v) : getAppVersion();
    }

    public int getSchemaVersion() {
        return currentSchemaVersion;
    }

    public boolean isDbUpdatedThisStartup() {
        return dbUpdatedThisStartup;
    }

    public Map<String, Object> getVersionInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", getAppVersion());
        info.put("frontendVersion", getFrontendVersion());
        info.put("backendVersion", getBackendVersion());
        info.put("schemaVersion", currentSchemaVersion);
        info.put("previousSchemaVersion", previousSchemaVersion);
        info.put("dbUpdated", dbUpdatedThisStartup);
        info.put("dbChanges", appliedDbChanges);
        info.put("releases", changelogRoot.getOrDefault("releases", Collections.emptyList()));
        return info;
    }

    public Map<String, Object> getChangelog() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", getAppVersion());
        map.put("releases", changelogRoot.getOrDefault("releases", Collections.emptyList()));
        return map;
    }

    /** 确认已读本次数据库升级提示（本进程内不再弹） */
    public void acknowledgeDbUpdate() {
        dbUpdatedThisStartup = false;
    }

    private Map<String, Object> defaultChangelog() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "1.0.0");
        root.put("frontendVersion", "1.0.0");
        root.put("backendVersion", "1.0.0");
        root.put("schemaVersion", 1);
        root.put("releases", Collections.emptyList());
        return root;
    }
}
