package com.gcoll.dbcompare.controller;

import com.gcoll.dbcompare.service.DataViewerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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
}
