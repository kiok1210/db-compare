package com.gcoll.dbcompare.service.datax;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成 DataX mysqlreader → mysqlwriter 作业 JSON。
 * 清表策略见 {@link DataxClearMode}；写入模式见 {@link DataxWriteMode}。
 */
public final class DataxJobBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private DataxJobBuilder() {
    }

    /**
     * @param splitPk 可为 null；非空时写入 reader.splitPk 以并行切片
     * @param channel DataX 通道数
     * @param batchSize writer batchSize
     * @param clearMode {@link DataxClearMode}；DATAX_DELETE/TRUNCATE 写入 writer.preSql
     */
    public static Map<String, Object> buildMysqlFullCopyJob(
            String srcJdbcUrl, String srcUser, String srcPassword, String srcTable,
            String tgtJdbcUrl, String tgtUser, String tgtPassword, String tgtTable,
            String splitPk, int channel, int batchSize, String clearMode) {
        return buildMysqlFullCopyJob(srcJdbcUrl, srcUser, srcPassword, srcTable,
                tgtJdbcUrl, tgtUser, tgtPassword, tgtTable,
                splitPk, channel, batchSize, clearMode, DataxWriteMode.INSERT, -1L,
                DataxClearMode.DEFAULT_DELETE_LIMIT);
    }

    /**
     * @param writeMode {@link DataxWriteMode#INSERT} 或 {@link DataxWriteMode#REPLACE}
     */
    public static Map<String, Object> buildMysqlFullCopyJob(
            String srcJdbcUrl, String srcUser, String srcPassword, String srcTable,
            String tgtJdbcUrl, String tgtUser, String tgtPassword, String tgtTable,
            String splitPk, int channel, int batchSize, String clearMode, String writeMode) {
        return buildMysqlFullCopyJob(srcJdbcUrl, srcUser, srcPassword, srcTable,
                tgtJdbcUrl, tgtUser, tgtPassword, tgtTable,
                splitPk, channel, batchSize, clearMode, writeMode, -1L,
                DataxClearMode.DEFAULT_DELETE_LIMIT);
    }

    /**
     * @param estimatedRows DATAX_DELETE：目标表 COUNT（仅用于 preSql 批次数）；写入仍全量读源
     * @param deleteLimit   DATAX_DELETE 每批 LIMIT
     */
    public static Map<String, Object> buildMysqlFullCopyJob(
            String srcJdbcUrl, String srcUser, String srcPassword, String srcTable,
            String tgtJdbcUrl, String tgtUser, String tgtPassword, String tgtTable,
            String splitPk, int channel, int batchSize, String clearMode, String writeMode,
            long estimatedRows, int deleteLimit) {

        Map<String, Object> speed = new LinkedHashMap<>();
        speed.put("channel", Math.max(1, channel));
        speed.put("byte", -1);

        Map<String, Object> errorLimit = new LinkedHashMap<>();
        errorLimit.put("record", 0);
        errorLimit.put("percentage", 0.02);

        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("speed", speed);
        setting.put("errorLimit", errorLimit);

        Map<String, Object> readerConn = new LinkedHashMap<>();
        readerConn.put("table", Collections.singletonList(srcTable));
        readerConn.put("jdbcUrl", Collections.singletonList(srcJdbcUrl));

        Map<String, Object> readerParam = new LinkedHashMap<>();
        readerParam.put("username", srcUser);
        readerParam.put("password", srcPassword);
        readerParam.put("column", Collections.singletonList("*"));
        if (splitPk != null && !splitPk.trim().isEmpty()) {
            readerParam.put("splitPk", splitPk.trim());
        }
        readerParam.put("connection", Collections.singletonList(readerConn));

        Map<String, Object> reader = new LinkedHashMap<>();
        reader.put("name", "mysqlreader");
        reader.put("parameter", readerParam);

        Map<String, Object> writerConn = new LinkedHashMap<>();
        writerConn.put("jdbcUrl", tgtJdbcUrl);
        writerConn.put("table", Collections.singletonList(tgtTable));

        Map<String, Object> writerParam = new LinkedHashMap<>();
        writerParam.put("writeMode", DataxWriteMode.normalize(writeMode));
        writerParam.put("username", tgtUser);
        writerParam.put("password", tgtPassword);
        writerParam.put("column", Collections.singletonList("*"));
        writerParam.put("batchSize", Math.max(100, batchSize));
        java.util.List<String> preSqls = DataxClearMode.buildPreSqlList(
                tgtTable, clearMode, estimatedRows, deleteLimit);
        if (preSqls != null && !preSqls.isEmpty()) {
            writerParam.put("preSql", preSqls);
        }
        writerParam.put("connection", Collections.singletonList(writerConn));

        Map<String, Object> writer = new LinkedHashMap<>();
        writer.put("name", "mysqlwriter");
        writer.put("parameter", writerParam);

        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("reader", reader);
        contentItem.put("writer", writer);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("setting", setting);
        job.put("content", Collections.singletonList(contentItem));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("job", job);
        return root;
    }

    /** @deprecated 使用带 clearMode / writeMode 的重载 */
    @Deprecated
    public static Map<String, Object> buildMysqlFullCopyJob(
            String srcJdbcUrl, String srcUser, String srcPassword, String srcTable,
            String tgtJdbcUrl, String tgtUser, String tgtPassword, String tgtTable,
            String splitPk, int channel, int batchSize, boolean truncateFirst) {
        return buildMysqlFullCopyJob(srcJdbcUrl, srcUser, srcPassword, srcTable,
                tgtJdbcUrl, tgtUser, tgtPassword, tgtTable,
                splitPk, channel, batchSize,
                truncateFirst ? DataxClearMode.TRUNCATE : DataxClearMode.NONE,
                DataxWriteMode.INSERT);
    }

    public static Path writeJobFile(Path dir, String fileName, Map<String, Object> job) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        String json = MAPPER.writeValueAsString(job);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
