package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.DataViewerExportService;
import com.gcoll.dbcompare.service.DataViewerService;
import com.gcoll.dbcompare.service.ViewerExportRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据浏览 Controller
 * 提供可视化库表查看、SQL 查询、分页功能
 */
@Controller
@RequestMapping("/viewer")
@Slf4j
public class DataViewerController {

    @Autowired
    private DataViewerService dataViewerService;

    @Autowired
    private DataViewerExportService dataViewerExportService;

    /**
     * 数据浏览主页面
     */
    @GetMapping("")
    public String viewerPage(Model model) {
        model.addAttribute("dataSources", dataViewerService.getAllDataSources());
        return "viewer/index";
    }

    /**
     * 获取数据源下的 schema/catalog 列表
     */
    @GetMapping("/schemas/{dsId}")
    @ResponseBody
    public Map<String, Object> getSchemas(@PathVariable Long dsId) {
        return dataViewerService.getSchemas(dsId);
    }

    /**
     * 获取指定模式下的表列表
     */
    @GetMapping("/tables/{dsId}")
    @ResponseBody
    public Map<String, Object> getTables(
            @PathVariable Long dsId,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return dataViewerService.getTables(dsId, catalog, schema);
    }

    /**
     * 分页查询表数据
     */
    @GetMapping("/table-data/{dsId}")
    @ResponseBody
    public Map<String, Object> getTableData(
            @PathVariable Long dsId,
            @RequestParam String table,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return dataViewerService.queryTableData(dsId, table, page, size, catalog, schema);
    }

    /**
     * 获取表列信息
     */
    @GetMapping("/columns/{dsId}")
    @ResponseBody
    public Map<String, Object> getColumns(
            @PathVariable Long dsId,
            @RequestParam String table,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return dataViewerService.getColumns(dsId, table, catalog, schema);
    }

    /**
     * 执行自定义 SQL 查询（分页）
     */
    @PostMapping("/sql-query/{dsId}")
    @ResponseBody
    public Map<String, Object> executeSql(
            @PathVariable Long dsId,
            @RequestParam String sql,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return dataViewerService.executeSql(dsId, sql, page, size);
    }

    /**
     * 获取表记录总数
     */
    @GetMapping("/count/{dsId}")
    @ResponseBody
    public Map<String, Object> getTableCount(
            @PathVariable Long dsId,
            @RequestParam String table,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        return dataViewerService.getTableCount(dsId, table, catalog, schema);
    }

    /**
     * 可导出的目标库方言列表
     */
    @GetMapping("/dialects")
    @ResponseBody
    public Map<String, Object> dialects() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("dialects", dataViewerExportService.listDialects());
        return result;
    }

    /**
     * 导出勾选表 / 选中行的 DDL、DML（默认目标方言为数据源自身类型）
     */
    @PostMapping("/export/{dsId}")
    @ResponseBody
    public ResponseEntity<?> exportSql(@PathVariable Long dsId, @RequestBody ViewerExportRequest request) {
        try {
            DataViewerExportService.ExportFile file = dataViewerExportService.export(dsId, request);
            String encoded = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8.name()).replace("+", "%20");
            String ascii = file.getFileName().replaceAll("[^a-zA-Z0-9._-]", "_");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType("application/sql;charset=UTF-8"))
                    .body(file.getSql().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("msg", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            log.error("导出 SQL 失败 dsId={}", dsId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("msg", e.getMessage() != null ? e.getMessage() : "导出失败");
            return ResponseEntity.status(500).body(err);
        }
    }
}
