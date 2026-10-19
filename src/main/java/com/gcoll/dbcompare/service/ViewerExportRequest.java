package com.gcoll.dbcompare.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据浏览导出 DDL/DML 请求。
 */
@Data
public class ViewerExportRequest {

    /** DDL / DML / BOTH */
    private String kind;

    /** 目标库方言；空则使用数据源自身类型 */
    private String targetDbType;

    private List<TableRef> tables = new ArrayList<>();

    /**
     * 单表按已选行导出 DML：列名 -> 值（与 {@link #tables} 仅一张表时生效）。
     */
    private List<Map<String, Object>> rows;

    /** DML 全表导出上限，默认 20000 */
    private Integer maxRows;

    private boolean includeDrop;

    @Data
    public static class TableRef {
        private String name;
        private String catalog;
        private String schema;
    }
}
