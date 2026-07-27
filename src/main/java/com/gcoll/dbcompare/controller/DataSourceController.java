package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.dictionary.DatabaseMetadataReader;
import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.service.ConnectionTestResult;
import com.gcoll.dbcompare.service.DataSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 数据源管理Controller
 * 提供数据源的CRUD操作和连接测试
 */
@Controller
@RequestMapping("/datasource")
@Slf4j
public class DataSourceController {
    
    @Autowired
    private DataSourceService dataSourceService;
    
    /**
     * 数据源管理页面
     */
    @GetMapping("/list")
    public String list(Model model) {
        List<DataSourceConfig> dataSources = dataSourceService.getAllDataSources();
        model.addAttribute("dataSources", dataSources);
        return "datasource/list";
    }
    
    /**
     * 添加数据源页面
     */
    @GetMapping("/add")
    public String addPage(Model model) {
        model.addAttribute("dataSource", new DataSourceConfig());
        return "datasource/form";
    }
    
    /**
     * 编辑数据源页面
     */
    @GetMapping("/edit/{id}")
    public String editPage(@PathVariable Long id, Model model) {
        DataSourceConfig dataSource = dataSourceService.getDataSourceById(id);
        dataSource.setPassword("");
        model.addAttribute("dataSource", dataSource);
        return "datasource/form";
    }
    
    /**
     * 保存数据源（AJAX）
     */
    @PostMapping("/save")
    @ResponseBody
    public Map<String, Object> save(@RequestBody DataSourceConfig config) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (config.getId() == null) {
                dataSourceService.addDataSource(config);
                result.put("msg", "添加成功");
            } else {
                dataSourceService.updateDataSource(config.getId(), config);
                result.put("msg", "更新成功");
            }
            result.put("success", true);
        } catch (Exception e) {
            log.error("保存数据源失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 删除数据源（AJAX）
     */
    @PostMapping("/delete/{id}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            dataSourceService.deleteDataSource(id);
            result.put("success", true);
            result.put("msg", "删除成功");
        } catch (Exception e) {
            log.error("删除数据源失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage());
        }
        
        return result;
    }

    /**
     * 导出数据源全量 SQL（MERGE）；同时刷新 scripts/datasource/full/ 快照。
     * 每次增删改另写入 incremental/ 增量脚本。
     */
    @GetMapping(value = "/export.sql", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<byte[]> exportSql() {
        try {
            String sql = dataSourceService.exportDataSourceSql();
            byte[] body = sql.getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"dbcompare_datasource_full.sql\"")
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(body);
        } catch (Exception e) {
            log.error("导出数据源 SQL 失败", e);
            String msg = "-- export failed: " + e.getMessage() + "\n";
            return ResponseEntity.status(500)
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    /**
     * 测试连接（AJAX，接收配置对象）
     */
    @PostMapping("/test")
    @ResponseBody
    public Map<String, Object> test(@RequestBody DataSourceConfig config) {
        Map<String, Object> result = new HashMap<>();
        if (config.getId() != null && (config.getPassword() == null || config.getPassword().isEmpty())) {
            DataSourceConfig saved = dataSourceService.getDataSourceById(config.getId());
            config.setPassword(saved.getPassword());
        }
        ConnectionTestResult testResult = dataSourceService.testConnection(config);
        result.put("success", testResult.isSuccess());
        result.put("msg", testResult.getMessage());
        return result;
    }
    
    /**
     * 测试连接（AJAX，接收数据源ID）
     */
    @PostMapping("/test/{id}")
    @ResponseBody
    public Map<String, Object> testById(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            ConnectionTestResult testResult = dataSourceService.testConnection(id);
            result.put("success", testResult.isSuccess());
            result.put("msg", testResult.getMessage());
        } catch (Exception e) {
            log.error("测试连接失败", e);
            result.put("success", false);
            result.put("msg", e.getMessage() != null ? e.getMessage() : "测试连接失败");
        }
        return result;
    }

    /**
     * 获取数据源的所有表（含行数估算，AJAX）
     * catalog 支持：空=全库、单库、逗号分隔多库；多库时表名返回 schema::table
     */
    @GetMapping("/tables/{id}")
    @ResponseBody
    public Map<String, Object> getTables(@PathVariable Long id,
                                         @RequestParam(required = false) String catalog,
                                         @RequestParam(required = false) String schema) {
        Map<String, Object> result = new HashMap<>();
        try {
            DataSourceConfig config = dataSourceService.getDataSourceById(id);
            DatabaseType dbType = config.getDbType();
            String effective = (catalog != null && !catalog.trim().isEmpty())
                    ? catalog.trim()
                    : (schema != null ? schema.trim() : "");

            List<String> tableNames;
            try (Connection conn1 = dataSourceService.createConnection(config)) {
                tableNames = readTablesForCompare(conn1, effective, null, dbType);
            }

            // 行数统计：表太多时跳过，避免选表页卡住；串行 COUNT 避免连接线程安全问题
            boolean countRows = tableNames.size() <= 120;
            List<TableInfo> tableInfos = new ArrayList<>();
            if (!countRows) {
                for (String tableName : tableNames) {
                    TableInfo info = new TableInfo();
                    info.setName(tableName);
                    info.setRowCount(null);
                    tableInfos.add(info);
                }
            } else {
                try (Connection conn2 = dataSourceService.createConnection(config);
                     Statement stmt = conn2.createStatement()) {
                    for (String tableName : tableNames) {
                        TableInfo info = new TableInfo();
                        info.setName(tableName);
                        String sqlName = toSqlQualifiedName(tableName, dbType);
                        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + sqlName)) {
                            if (rs.next()) {
                                info.setRowCount(rs.getLong(1));
                            }
                        } catch (Exception e) {
                            info.setRowCount(null);
                        }
                        tableInfos.add(info);
                    }
                }
            }

            tableInfos.sort(Comparator.comparing(TableInfo::getName, String.CASE_INSENSITIVE_ORDER));
            result.put("success", true);
            result.put("tables", tableInfos);
            result.put("totalCount", tableInfos.size());
            result.put("sourceName", config.getName());
            result.put("sourceId", id);
            result.put("counted", countRows);
        } catch (Exception e) {
            log.error("获取表列表失败: datasourceId={}", id, e);
            result.put("success", false);
            result.put("msg", "获取表列表失败: " + e.getMessage());
        }
        return result;
    }

    /** schema::table → `schema`.`table`（用于 COUNT SQL） */
    private String toSqlQualifiedName(String tableName, DatabaseType dbType) {
        if (tableName == null) {
            return tableName;
        }
        if (tableName.contains("::")) {
            String[] parts = tableName.split("::", 2);
            if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
                return "`" + parts[0] + "`.`" + parts[1] + "`";
            }
            return parts[0] + "." + parts[1];
        }
        if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.GOLDENDB) {
            return "`" + tableName + "`";
        }
        return tableName;
    }

    /**
     * 获取两个数据源的表列表交集（AJAX）
     */
    @GetMapping("/tables-compare")
    @ResponseBody
    public Map<String, Object> getCompareTables(
            @RequestParam Long sourceId, @RequestParam Long targetId,
            @RequestParam(required = false) String srcCatalog,
            @RequestParam(required = false) String srcSchema,
            @RequestParam(required = false) String tgtCatalog,
            @RequestParam(required = false) String tgtSchema) {
        Map<String, Object> result = new HashMap<>();
        Connection sourceConn = null;
        Connection targetConn = null;
        try {
            DataSourceConfig sourceCfg = dataSourceService.getDataSourceById(sourceId);
            DataSourceConfig targetCfg = dataSourceService.getDataSourceById(targetId);

            sourceConn = dataSourceService.createConnection(sourceCfg);
            targetConn = dataSourceService.createConnection(targetCfg);

            List<String> sourceTables = readTablesForCompare(sourceConn, srcCatalog, srcSchema, sourceCfg.getDbType());
            List<String> targetTables = readTablesForCompare(targetConn, tgtCatalog, tgtSchema, targetCfg.getDbType());

            // 判断是否为多库/全库模式（表名带 schema:: 前缀）
            boolean isMultiSchema = sourceTables.stream().anyMatch(t -> t.contains("::"))
                    || targetTables.stream().anyMatch(t -> t.contains("::"));

            // 计算交集、仅在源库、仅在目标库
            // 多库模式：按纯表名（去前缀）做交集，但返回时保留原始带前缀的表名
            Set<String> sourceSet;
            Set<String> targetSet;
            if (isMultiSchema) {
                sourceSet = sourceTables.stream()
                        .map(t -> t.contains("::") ? t.substring(t.indexOf("::") + 2).toLowerCase() : t.toLowerCase())
                        .collect(Collectors.toSet());
                targetSet = targetTables.stream()
                        .map(t -> t.contains("::") ? t.substring(t.indexOf("::") + 2).toLowerCase() : t.toLowerCase())
                        .collect(Collectors.toSet());
            } else {
                sourceSet = sourceTables.stream()
                        .map(String::toLowerCase).collect(Collectors.toSet());
                targetSet = targetTables.stream()
                        .map(String::toLowerCase).collect(Collectors.toSet());
            }

            Set<String> commonSet = new HashSet<>(sourceSet);
            commonSet.retainAll(targetSet);

            Set<String> onlySourceSet = new HashSet<>(sourceSet);
            onlySourceSet.removeAll(targetSet);

            Set<String> onlyTargetSet = new HashSet<>(targetSet);
            onlyTargetSet.removeAll(sourceSet);

            // 匹配回原始大小写
            List<String> common = sourceTables.stream()
                    .filter(t -> {
                        String key = isMultiSchema && t.contains("::")
                                ? t.substring(t.indexOf("::") + 2).toLowerCase() : t.toLowerCase();
                        return commonSet.contains(key);
                    })
                    .collect(Collectors.toList());
            List<String> onlySource = sourceTables.stream()
                    .filter(t -> {
                        String key = isMultiSchema && t.contains("::")
                                ? t.substring(t.indexOf("::") + 2).toLowerCase() : t.toLowerCase();
                        return onlySourceSet.contains(key);
                    })
                    .collect(Collectors.toList());
            List<String> onlyTarget = targetTables.stream()
                    .filter(t -> {
                        String key = isMultiSchema && t.contains("::")
                                ? t.substring(t.indexOf("::") + 2).toLowerCase() : t.toLowerCase();
                        return onlyTargetSet.contains(key);
                    })
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("commonTables", common);
            result.put("onlyInSource", onlySource);
            result.put("onlyInTarget", onlyTarget);
            result.put("commonCount", common.size());
            result.put("onlySourceCount", onlySource.size());
            result.put("onlyTargetCount", onlyTarget.size());

        } catch (Exception e) {
            log.error("获取比对表列表失败", e);
            result.put("success", false);
            result.put("msg", "获取比对表列表失败: " + e.getMessage());
        } finally {
            if (sourceConn != null) try { sourceConn.close(); } catch (Exception ex) {}
            if (targetConn != null) try { targetConn.close(); } catch (Exception ex) {}
        }
        return result;
    }

    /**
     * 读取表列表（支持多库/全库模式）
     * 单库模式：返回纯表名列表
     * 多库/全库模式：返回 schema::table 格式列表（保留前缀，不做去重）
     */
    private List<String> readTablesForCompare(Connection conn, String catalog, String schema, DatabaseType dbType) throws Exception {
        List<String> rawTables;
        if ((catalog == null || catalog.isEmpty()) && (schema == null || schema.isEmpty())) {
            // "全库"模式：返回 schema::table 格式
            rawTables = DatabaseMetadataReader.readAllTablesFromAllSchemas(conn, dbType);
        } else {
            String effectiveSchema = catalog != null && !catalog.isEmpty() ? catalog : schema;
            if (effectiveSchema != null && effectiveSchema.contains(",")) {
                // 多选模式：逐个库收集，加上前缀
                rawTables = new ArrayList<>();
                for (String s : effectiveSchema.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        for (String t : DatabaseMetadataReader.readAllTables(conn, trimmed, dbType)) {
                            rawTables.add(trimmed + "::" + t);
                        }
                    }
                }
            } else {
                // 单库模式：直接返回
                return DatabaseMetadataReader.readAllTables(conn, effectiveSchema, dbType);
            }
        }
        // 多库/全库模式：直接返回带前缀的表名列表
        return new ArrayList<>(rawTables);
    }

    /**
     * 表信息（名称+行数）
     */
    public static class TableInfo {
        private String name;
        private Long rowCount;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getRowCount() { return rowCount; }
        public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    }
}
