package com.gcoll.dbcompare.util;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 类 Navicat「数据同步」：按主键有序归并比较辅助方法。
 */
public final class SyncDataSyncHelper {

    private SyncDataSyncHelper() {
    }

    public static int[] primaryKeyIndexes(List<String> columns, List<String> primaryKeys) {
        if (columns == null || primaryKeys == null || primaryKeys.isEmpty()) {
            return new int[0];
        }
        int[] idx = new int[primaryKeys.size()];
        for (int i = 0; i < primaryKeys.size(); i++) {
            String pk = primaryKeys.get(i);
            int found = -1;
            for (int c = 0; c < columns.size(); c++) {
                if (columns.get(c) != null && columns.get(c).equalsIgnoreCase(pk)) {
                    found = c;
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("主键列不在表字段中: " + pk);
            }
            idx[i] = found;
        }
        return idx;
    }

    /** 按主键列比较两行：&lt;0 源更小，0 相等，&gt;0 源更大 */
    public static int comparePrimaryKeys(Object[] srcRow, Object[] tgtRow, int[] pkIndexes) {
        for (int pkIndex : pkIndexes) {
            int c = compareValues(srcRow[pkIndex], tgtRow[pkIndex]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** 非主键列是否完全一致（null 安全） */
    public static boolean nonPrimaryEquals(Object[] srcRow, Object[] tgtRow, int[] pkIndexes) {
        boolean[] isPk = new boolean[srcRow.length];
        for (int pkIndex : pkIndexes) {
            if (pkIndex >= 0 && pkIndex < isPk.length) {
                isPk[pkIndex] = true;
            }
        }
        for (int i = 0; i < srcRow.length; i++) {
            if (isPk[i]) {
                continue;
            }
            if (compareValues(srcRow[i], tgtRow[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareValues(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return compareNumbers((Number) a, (Number) b);
        }
        if (a instanceof byte[] && b instanceof byte[]) {
            return Arrays.equals((byte[]) a, (byte[]) b) ? 0 : Arrays.hashCode((byte[]) a) - Arrays.hashCode((byte[]) b);
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int compareNumbers(Number a, Number b) {
        if (a instanceof BigDecimal || b instanceof BigDecimal
                || a instanceof Double || b instanceof Double
                || a instanceof Float || b instanceof Float) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        long la = a.longValue();
        long lb = b.longValue();
        return Long.compare(la, lb);
    }

    public static String buildOrderBy(List<String> primaryKeys, java.util.function.Function<String, String> quote) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote.apply(primaryKeys.get(i)));
        }
        return sb.toString();
    }

    /**
     * 稳定主键键（用于 IN 查询结果回填 Map）。不追求跨类型完美，仅需同表内可区分。
     */
    public static String pkMapKey(Object[] row, int[] pkIndexes) {
        if (row == null || pkIndexes == null || pkIndexes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < pkIndexes.length; i++) {
            if (i > 0) {
                sb.append('\u0001');
            }
            Object v = row[pkIndexes[i]];
            if (v == null) {
                sb.append('\u0000');
            } else if (v instanceof byte[]) {
                sb.append(Arrays.hashCode((byte[]) v));
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    /** 仅主键列的 Map 键（与 {@link #pkMapKey(Object[], int[])} 一致）。 */
    public static String pkValuesMapKey(Object[] pkValues) {
        if (pkValues == null || pkValues.length == 0) {
            return "";
        }
        int[] idx = new int[pkValues.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        return pkMapKey(pkValues, idx);
    }
}
