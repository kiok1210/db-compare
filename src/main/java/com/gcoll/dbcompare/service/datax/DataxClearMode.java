package com.gcoll.dbcompare.service.datax;

/**
 * DataX 全量同步前如何清空目标表。
 * <ul>
 *   <li>{@link #AUTO}：应用侧先 JDBC TRUNCATE，失败则分批 DELETE</li>
 *   <li>{@link #TRUNCATE}：应用侧仅 TRUNCATE</li>
 *   <li>{@link #DELETE}：应用侧分批 DELETE（LIMIT，偏慢）</li>
 *   <li>{@link #DATAX_DELETE}：不清 JDBC；由 DataX writer preSql 一次 {@code DELETE FROM}（通常快于分批）</li>
 *   <li>{@link #DATAX_TRUNCATE}：不清 JDBC；由 DataX writer preSql {@code TRUNCATE}（最快，需 DDL）</li>
 *   <li>{@link #NONE}：不清空</li>
 * </ul>
 */
public final class DataxClearMode {

    public static final String AUTO = "AUTO";
    public static final String TRUNCATE = "TRUNCATE";
    public static final String DELETE = "DELETE";
    /** 由 DataX 作业 preSql 执行整表 DELETE（跳过应用侧分批 DELETE） */
    public static final String DATAX_DELETE = "DATAX_DELETE";
    /** 由 DataX 作业 preSql 执行 TRUNCATE */
    public static final String DATAX_TRUNCATE = "DATAX_TRUNCATE";
    public static final String NONE = "NONE";

    private DataxClearMode() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return AUTO;
        }
        String m = mode.trim().toUpperCase();
        if (TRUNCATE.equals(m) || DELETE.equals(m) || NONE.equals(m) || AUTO.equals(m)
                || DATAX_DELETE.equals(m) || DATAX_TRUNCATE.equals(m)) {
            return m;
        }
        return AUTO;
    }

    /** 应用侧 JDBC 清表（AUTO/DELETE/TRUNCATE） */
    public static boolean isAppSideClear(String mode) {
        String m = normalize(mode);
        return AUTO.equals(m) || DELETE.equals(m) || TRUNCATE.equals(m);
    }

    /** 由 DataX writer.preSql 清表（DATAX_DELETE / DATAX_TRUNCATE） */
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
        if (DATAX_DELETE.equals(m)) {
            return "DataX DELETE（preSql 整表删除）";
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
     * 写入 DataX 作业 preSql。
     * {@link #DATAX_DELETE}/{@link #DATAX_TRUNCATE} 及兼容旧的 TRUNCATE/DELETE 入参会生成 SQL；
     * AUTO/NONE/应用侧模式调用方应传 NONE。
     */
    public static String buildPreSql(String tableName, String mode) {
        String quoted = quoteMysqlTable(tableName);
        if (quoted == null) {
            return null;
        }
        String m = normalize(mode);
        if (TRUNCATE.equals(m) || DATAX_TRUNCATE.equals(m)) {
            return "TRUNCATE TABLE " + quoted;
        }
        if (DELETE.equals(m) || DATAX_DELETE.equals(m)) {
            return "DELETE FROM " + quoted;
        }
        return null;
    }

    /** {@code db.table} → {@code `db`.`table`}；裸表名 → {@code `table`} */
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
