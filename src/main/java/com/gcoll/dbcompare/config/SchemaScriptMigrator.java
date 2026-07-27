package com.gcoll.dbcompare.config;

import com.gcoll.dbcompare.entity.AppMeta;
import com.gcoll.dbcompare.repository.AppMetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用库增量 DDL：扫描 classpath {@code db/schema/Vn__*.sql}，按版本顺序执行。
 * 内网升级只同步程序即可；勿覆盖 {@code data/datasource*}（数据源独立库）与内网自有 {@code data/dbcompare*}。
 */
@Component
public class SchemaScriptMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaScriptMigrator.class);
    private static final String SCRIPT_PATTERN = "classpath*:db/schema/V*.sql";
    private static final Pattern VERSION_FILE = Pattern.compile("^V(\\d+)__(.+)\\.sql$", Pattern.CASE_INSENSITIVE);

    private final DataSource dataSource;
    private final AppMetaRepository appMetaRepository;

    public SchemaScriptMigrator(DataSource dataSource, AppMetaRepository appMetaRepository) {
        this.dataSource = dataSource;
        this.appMetaRepository = appMetaRepository;
    }

    /**
     * 执行 {@code fromExclusive < n <= toInclusive} 的脚本；每成功一个回写 SCHEMA_VERSION。
     *
     * @return 本次成功应用的脚本版本号（升序）
     */
    public List<Integer> apply(int fromExclusive, int toInclusive) {
        if (toInclusive <= fromExclusive) {
            return Collections.emptyList();
        }
        List<SchemaScript> scripts;
        try {
            scripts = loadScripts();
        } catch (Exception e) {
            log.error("扫描 schema 增量脚本失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        List<Integer> applied = new ArrayList<>();
        for (SchemaScript script : scripts) {
            if (script.version <= fromExclusive || script.version > toInclusive) {
                continue;
            }
            try {
                executeScript(script);
                saveSchemaVersion(script.version);
                applied.add(script.version);
                log.info("已应用 schema 增量脚本 V{} ({})", script.version, script.resourceName);
            } catch (Exception e) {
                log.error("应用 schema 脚本 V{} 失败，中止后续迁移: {}", script.version, e.getMessage());
                break;
            }
        }
        return applied;
    }

    List<SchemaScript> loadScripts() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(SCRIPT_PATTERN);
        List<SchemaScript> list = new ArrayList<>();
        for (Resource r : resources) {
            String name = r.getFilename();
            if (name == null) {
                continue;
            }
            Matcher m = VERSION_FILE.matcher(name);
            if (!m.matches()) {
                log.warn("忽略不符合命名的 schema 脚本: {}", name);
                continue;
            }
            int ver = Integer.parseInt(m.group(1));
            String body = readResource(r);
            list.add(new SchemaScript(ver, name, body));
        }
        list.sort(Comparator.comparingInt(s -> s.version));
        return list;
    }

    static int parseVersion(String fileName) {
        if (fileName == null) {
            return -1;
        }
        Matcher m = VERSION_FILE.matcher(fileName);
        if (!m.matches()) {
            return -1;
        }
        return Integer.parseInt(m.group(1));
    }

    private void executeScript(SchemaScript script) throws Exception {
        List<String> statements = splitStatements(script.body);
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    st.execute(trimmed);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains("already") || msg.contains("not found") || msg.contains("does not exist")
                            || msg.contains("duplicate")) {
                        log.warn("schema V{} 语句可忽略: {} — {}", script.version, trimmed, e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /** 按分号拆分，忽略行注释与空语句 */
    static List<String> splitStatements(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            // 简单按行尾 ; 切分（DDL 脚本不嵌套过程体）
            String soFar = current.toString();
            int semi = soFar.lastIndexOf(';');
            if (semi >= 0) {
                String stmt = soFar.substring(0, semi).trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                current = new StringBuilder(soFar.substring(semi + 1));
            }
        }
        String rest = current.toString().trim();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    private void saveSchemaVersion(int version) {
        AppMeta meta = appMetaRepository.findById(AppMeta.KEY_SCHEMA_VERSION).orElseGet(AppMeta::new);
        meta.setMetaKey(AppMeta.KEY_SCHEMA_VERSION);
        meta.setMetaValue(String.valueOf(version));
        meta.setUpdateTime(LocalDateTime.now());
        appMetaRepository.save(meta);
    }

    private static String readResource(Resource r) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static final class SchemaScript {
        final int version;
        final String resourceName;
        final String body;

        SchemaScript(int version, String resourceName, String body) {
            this.version = version;
            this.resourceName = resourceName;
            this.body = body;
        }
    }
}
