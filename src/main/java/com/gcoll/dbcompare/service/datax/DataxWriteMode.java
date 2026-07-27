package com.gcoll.dbcompare.service.datax;

/**
 * DataX mysqlwriter 写入模式（可选手动配置）。
 * <ul>
 *   <li>{@link #INSERT}：普通 INSERT，目标已有主键会冲突（清表后全量适用）</li>
 *   <li>{@link #REPLACE}：REPLACE INTO，冲突则先删后插；可不清表覆盖已有行（不删目标多余行）</li>
 * </ul>
 */
public final class DataxWriteMode {

    public static final String INSERT = "insert";
    public static final String REPLACE = "replace";

    private DataxWriteMode() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return INSERT;
        }
        String m = mode.trim().toLowerCase();
        if (REPLACE.equals(m) || "replace into".equals(m)) {
            return REPLACE;
        }
        if (INSERT.equals(m)) {
            return INSERT;
        }
        String u = mode.trim().toUpperCase();
        if ("REPLACE".equals(u)) {
            return REPLACE;
        }
        if ("INSERT".equals(u)) {
            return INSERT;
        }
        return INSERT;
    }

    public static String displayName(String mode) {
        if (REPLACE.equals(normalize(mode))) {
            return "replace（REPLACE INTO，冲突覆盖）";
        }
        return "insert（普通插入）";
    }
}
