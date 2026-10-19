package com.gcoll.dbcompare.service.datax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DataX 全量同步前如何清空目标表。
 * <ul>
 *   <li>{@link #AUTO}：应用侧先 JDBC TRUNCATE，失败则分批 DELETE</li>
 *   <li>{@link #TRUNCATE}：应用侧仅 TRUNCATE</li>
 *   <li>{@link #DELETE}：应用侧分批 DELETE（LIMIT 循环直到清空）</li>
 *   <li>{@link #PARALLEL_DELETE}：应用侧按主键区间多线程分批 DELETE，再 DataX 写入（无 preSql）</li>
 *   <li>{@link #DATAX_DELETE}：DataX writer.preSql 多条 {@code DELETE…LIMIT}（按<strong>目标表 COUNT</strong>算批次数）；
 *       随后 DataX 按<strong>源表</strong>全量写入（写入进度用源表 COUNT，绝不按目标行数截断源表）</li>
 *   <li>{@link #DATAX_TRUNCATE}：DataX writer preSql {@code TRUNCATE}</li>
 *   <li>{@link #NONE}：不清空</li>
 * </ul>
 */
public final class DataxClearMode {

    public static final String AUTO = "AUTO";
    public static final String TRUNCATE = "TRUNCATE";
    public static final String DELETE = "DELETE";
    /** 应用侧：主键区间并行 DELETE，完成后 DataX 作业不再 preSql 清表 */
    public static final String PARALLEL_DELETE = "PARALLEL_DELETE";
    /** DataX preSql：按目标 COUNT 生成 DELETE…LIMIT；写入仍全量拷源 */
    public static final String DATAX_DELETE = "DATAX_DELETE";
    public static final String DATAX_TRUNCATE = "DATAX_TRUNCATE";
    public static final String NONE = "NONE";

    public static final int DEFAULT_DELETE_LIMIT = 50_000;
    public static final int MAX_BATCH_STATEMENTS = 2_000;
    /** 目标 COUNT 未知时按此规模生成批次数 */
    public static final long UNKNOWN_ROWS_ASSUME = 10_000_000L;
    /** COUNT 后额外多跑几批，兜底并发写入 */
    public static final int EXTRA_SAFETY_BATCHES = 5;

    private DataxClearMode() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return AUTO;
        }
        String m = mode.trim().toUpperCase();
        if (TRUNCATE.equals(m) || DELETE.equals(m) || NONE.equals(m) || AUTO.equals(m)
                || PARALLEL_DELETE.equals(m)
                || DATAX_DELETE.equals(m) || DATAX_TRUNCATE.equals(m)) {
            return m;
        }
        return AUTO;
    }

    /**
     * 任务级覆盖全局：taskMode 非空则用之，否则 settingsMode；都空则 AUTO。
     */
    public static String resolve(String taskMode, String settingsMode) {
        if (taskMode != null && !taskMode.trim().isEmpty()) {
            return normalize(taskMode);
        }
        return normalize(settingsMode);
    }

    public static boolean isAppSideClear(String mode) {
        String m = normalize(mode);
        return AUTO.equals(m) || DELETE.equals(m) || TRUNCATE.equals(m) || PARALLEL_DELETE.equals(m);
    }

    public static boolean isDataxSideClear(String mode) {
        String m = normalize(mode);
        return DATAX_DELETE.equals(m) || DATAX_TRUNCATE.equals(m);
    }

    public static String displayName(String mode) {
        String m = normalize(mode);
        if (TRUNCATE.equals(m)) {
            return "应用侧 TRUNCATE";
        }
        if (DELETE.equals(m)) {
            return "应用侧分批 DELETE";
        }
        if (PARALLEL_DELETE.equals(m)) {
            return "应用侧并行分段 DELETE（按主键）";
        }
        if (DATAX_DELETE.equals(m)) {
            return "DataX 分批 DELETE（preSql，按目标 COUNT）";
        }
        if (DATAX_TRUNCATE.equals(m)) {
            return "DataX TRUNCATE（preSql）";
        }
        if (NONE.equals(m)) {
            return "不清空";
        }
        return "自动（应用侧 TRUNCATE→分批 DELETE）";
    }

    /**
     * @param targetRowCount DATAX_DELETE 用：目标表 COUNT（清表批次数）；&lt;0 未知；0 不生成 DELETE
     */
    public static List<String> buildPreSqlList(String tableName, String mode,
                                               long targetRowCount, int deleteLimit) {
        String quoted = quoteMysqlTable(tableName);
        if (quoted == null) {
            return Collections.emptyList();
        }
        String m = normalize(mode);
        if (TRUNCATE.equals(m) || DATAX_TRUNCATE.equals(m)) {
            return Collections.singletonList("TRUNCATE TABLE " + quoted);
        }
        if (DATAX_DELETE.equals(m)) {
            return buildBatchDeletePreSql(quoted, targetRowCount, deleteLimit);
        }
        return Collections.emptyList();
    }

    @Deprecated
    public static String buildPreSql(String tableName, String mode) {
        List<String> list = buildPreSqlList(tableName, mode, -1L, DEFAULT_DELETE_LIMIT);
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        return list.get(0) + " /* +" + (list.size() - 1) + " more batch DELETE */";
    }

    static List<String> buildBatchDeletePreSql(String quotedTable, long targetRows, int deleteLimit) {
        int limit = deleteLimit > 0 ? deleteLimit : DEFAULT_DELETE_LIMIT;
        if (targetRows == 0) {
            return Collections.emptyList();
        }
        long rows = targetRows < 0 ? UNKNOWN_ROWS_ASSUME : targetRows;
        int batches;
        if (rows <= 0) {
            batches = 1;
        } else {
            long need = (rows + limit - 1L) / limit + EXTRA_SAFETY_BATCHES;
            batches = (int) Math.min(MAX_BATCH_STATEMENTS, Math.max(1L, need));
        }
        String sql = "DELETE FROM " + quotedTable + " LIMIT " + limit;
        List<String> out = new ArrayList<>(batches);
        for (int i = 0; i < batches; i++) {
            out.add(sql);
        }
        return out;
    }

    public static int calcBatchCount(long targetRows, int deleteLimit) {
        if (targetRows == 0) {
            return 0;
        }
        int limit = deleteLimit > 0 ? deleteLimit : DEFAULT_DELETE_LIMIT;
        long rows = targetRows < 0 ? UNKNOWN_ROWS_ASSUME : targetRows;
        if (rows <= 0) {
            return 1;
        }
        long need = (rows + limit - 1L) / limit + EXTRA_SAFETY_BATCHES;
        return (int) Math.min(MAX_BATCH_STATEMENTS, Math.max(1L, need));
    }

    /**
     * 将 [min, max]（含两端）切成 parts 个互不重叠半开区间 [lo, hi)，末段 hi=max+1。
     * 返回 long[2]={lo, hiExclusive}；parts≤1 或 max&lt;min 返回空。
     */
    public static List<long[]> splitPkRanges(long min, long max, int parts) {
        if (parts < 1 || max < min) {
            return Collections.emptyList();
        }
        int n = Math.max(1, parts);
        long span = max - min + 1L;
        if (span <= 0) {
            return Collections.emptyList();
        }
        if (n > span) {
            n = (int) span;
        }
        List<long[]> out = new ArrayList<>(n);
        long step = span / n;
        long rem = span % n;
        long cursor = min;
        for (int i = 0; i < n; i++) {
            long size = step + (i < rem ? 1 : 0);
            long hi = cursor + size;
            out.add(new long[]{cursor, hi});
            cursor = hi;
        }
        return out;
    }

    static String quoteMysqlTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        String t = tableName.replace("`", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        int dot = t.indexOf('.');
        if (dot > 0 && dot < t.length() - 1) {
            return "`" + t.substring(0, dot).trim() + "`.`" + t.substring(dot + 1).trim() + "`";
        }
        return "`" + t + "`";
    }
}
