package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据源 SQL 导出：全量快照 + 每次增删改增量脚本。
 * <p>
 * 密码保持库中 ENC: 密文；恢复时需与 {@code db-compare.encryption.key} 一致。
 */
@Service
@Slf4j
public class DataSourceScriptExporter {

    public enum ChangeOp {
        INSERT, UPDATE, DELETE
    }

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter COMMENT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SQL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final DatasourceConfigRepository DatasourceConfigRepository;
    private final Path baseDir;
    private final AtomicLong seq = new AtomicLong(0);

    public DataSourceScriptExporter(
            DatasourceConfigRepository DatasourceConfigRepository,
            @Value("${db-compare.datasource-scripts.dir:./scripts/datasource}") String baseDir) {
        this.DatasourceConfigRepository = DatasourceConfigRepository;
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    Path getBaseDir() {
        return baseDir;
    }

    /** 低于演示数据初始化，确保首次全量包含演示数据源 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        try {
            writeFullSnapshot();
            log.info("数据源全量导出脚本已刷新: {}", fullSqlPath());
        } catch (Exception e) {
            log.warn("启动时导出数据源脚本失败: {}", e.getMessage());
        }
    }

    /** 供下载/手工调用：构建并落盘全量 SQL，返回正文 */
    public String exportFullAndPersist() throws IOException {
        return writeFullSnapshot();
    }

    public String buildFullExportSql(List<DataSourceConfig> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================================================\n");
        sb.append("-- db-compare 数据源全量导出\n");
        sb.append("-- 生成时间: ").append(LocalDateTime.now().format(COMMENT_TS)).append("\n");
        sb.append("-- 表: dbcompare_datasource（H2 / Hibernate snake_case）\n");
        sb.append("-- 密码为 ENC: 密文，恢复环境须使用相同 db-compare.encryption.key\n");
        sb.append("-- ============================================================\n\n");
        sb.append("-- 可选：先清空再灌入（按需取消注释）\n");
        sb.append("-- DELETE FROM dbcompare_datasource;\n\n");

        if (list == null || list.isEmpty()) {
            sb.append("-- （当前无数据源）\n");
            return sb.toString();
        }
        for (DataSourceConfig ds : list) {
            sb.append(buildUpsertSql(ds)).append("\n");
        }
        return sb.toString();
    }

    public String buildUpsertSql(DataSourceConfig ds) {
        return "-- UPSERT id=" + ds.getId() + " name=" + nullSafe(ds.getName()) + "\n"
                + "MERGE INTO dbcompare_datasource ("
                + "id, name, db_type, url, username, password, driver_class, "
                + "\"schema\", description, enabled, create_time, update_time"
                + ") KEY(id) VALUES ("
                + sqlLong(ds.getId()) + ", "
                + sqlStr(ds.getName()) + ", "
                + sqlStr(ds.getDbType() != null ? ds.getDbType().name() : null) + ", "
                + sqlStr(ds.getUrl()) + ", "
                + sqlStr(ds.getUsername()) + ", "
                + sqlStr(ds.getPassword()) + ", "
                + sqlStr(ds.getDriverClass()) + ", "
                + sqlStr(ds.getSchema()) + ", "
                + sqlStr(ds.getDescription()) + ", "
                + sqlBool(ds.getEnabled()) + ", "
                + sqlTimestamp(ds.getCreateTime()) + ", "
                + sqlTimestamp(ds.getUpdateTime())
                + ");\n";
    }

    public String buildDeleteSql(Long id, String name) {
        return "-- DELETE id=" + id + " name=" + nullSafe(name) + "\n"
                + "DELETE FROM dbcompare_datasource WHERE id = " + sqlLong(id) + ";\n";
    }

    public String buildIncrementalSql(ChangeOp op, DataSourceConfig ds, Long deletedId, String deletedName) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================================================\n");
        sb.append("-- db-compare 数据源增量脚本\n");
        sb.append("-- 操作: ").append(op.name()).append("\n");
        sb.append("-- 时间: ").append(LocalDateTime.now().format(COMMENT_TS)).append("\n");
        sb.append("-- ============================================================\n\n");
        if (op == ChangeOp.DELETE) {
            sb.append(buildDeleteSql(deletedId, deletedName));
        } else if (ds != null) {
            sb.append(buildUpsertSql(ds));
        }
        return sb.toString();
    }

    public void recordChange(ChangeOp op, DataSourceConfig afterSaveOrNull, Long deletedId, String deletedName) {
        try {
            Files.createDirectories(incrementalDir());
            Files.createDirectories(fullDir());
            String body = buildIncrementalSql(op, afterSaveOrNull, deletedId, deletedName);
            String nameHint = op == ChangeOp.DELETE
                    ? ("id" + deletedId)
                    : ("id" + (afterSaveOrNull != null ? afterSaveOrNull.getId() : "x"));
            String fileName = LocalDateTime.now().format(FILE_TS)
                    + "_" + String.format("%04d", seq.incrementAndGet() % 10000)
                    + "_" + op.name().toLowerCase()
                    + "_" + sanitizeFileToken(nameHint)
                    + ".sql";
            Path file = incrementalDir().resolve(fileName);
            Files.write(file, body.getBytes(StandardCharsets.UTF_8));
            appendChangelog(op, file.getFileName().toString(), afterSaveOrNull, deletedId, deletedName);
            writeFullSnapshot();
            log.info("数据源增量脚本已写入: {}", file);
        } catch (Exception e) {
            log.warn("写入数据源增量脚本失败({}): {}", op, e.getMessage());
        }
    }

    private String writeFullSnapshot() throws IOException {
        Files.createDirectories(fullDir());
        List<DataSourceConfig> all = DatasourceConfigRepository.findAll();
        String sql = buildFullExportSql(all);
        Files.write(fullSqlPath(), sql.getBytes(StandardCharsets.UTF_8));
        return sql;
    }

    private void appendChangelog(ChangeOp op, String fileName, DataSourceConfig ds,
                                 Long deletedId, String deletedName) throws IOException {
        Path logFile = baseDir.resolve("CHANGELOG.md");
        if (!Files.exists(logFile)) {
            Files.write(logFile, ("# 数据源脚本变更日志\n\n"
                    + "| 时间 | 操作 | 对象 | 文件 |\n|------|------|------|------|\n").getBytes(StandardCharsets.UTF_8));
        }
        String obj = op == ChangeOp.DELETE
                ? ("#" + deletedId + " " + nullSafe(deletedName))
                : ("#" + ds.getId() + " " + nullSafe(ds.getName()));
        String line = "| " + LocalDateTime.now().format(COMMENT_TS) + " | " + op.name()
                + " | " + obj.replace("|", "/") + " | `incremental/" + fileName + "` |\n";
        Files.write(logFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private Path fullDir() {
        return baseDir.resolve("full");
    }

    private Path incrementalDir() {
        return baseDir.resolve("incremental");
    }

    private Path fullSqlPath() {
        return fullDir().resolve("dbcompare_datasource_full.sql");
    }

    static String sqlStr(String v) {
        if (v == null) {
            return "NULL";
        }
        return "'" + v.replace("'", "''") + "'";
    }

    static String sqlLong(Long v) {
        return v == null ? "NULL" : String.valueOf(v);
    }

    static String sqlBool(Boolean v) {
        if (v == null) {
            return "TRUE";
        }
        return v ? "TRUE" : "FALSE";
    }

    static String sqlTimestamp(LocalDateTime t) {
        if (t == null) {
            return "NULL";
        }
        return "TIMESTAMP '" + t.format(SQL_TS) + "'";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String sanitizeFileToken(String s) {
        if (s == null || s.isEmpty()) {
            return "x";
        }
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
