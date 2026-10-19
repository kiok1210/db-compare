package com.gcoll.dbcompare.service.datax;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 混合同步模式路由：按源表估算行数在 JDBC 全量与 DataX 之间切换。
 */
public final class SyncHybridRouter {

    /** 达到该行数（含）且已配置 DataX 时走 DataX */
    public static final long DATAX_ROW_THRESHOLD = 1_000_000L;

    private SyncHybridRouter() {
    }

    /**
     * 是否已配置可用的 DataX（能解析到 datax.py）。
     */
    public static boolean isDataxConfigured(String dataxHome) {
        if (dataxHome == null || dataxHome.trim().isEmpty()) {
            return false;
        }
        try {
            Path py = DataxProcessRunner.resolveDataxPy(dataxHome);
            return py != null && Files.isRegularFile(py);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 混合模式是否对本表使用 DataX。
     * <ul>
     *   <li>估算行数未知（&lt;0）→ 否（走普通 DML）</li>
     *   <li>估算行数 &lt; {@link #DATAX_ROW_THRESHOLD} → 否</li>
     *   <li>≥ 阈值且已配置 DataX → 是</li>
     *   <li>≥ 阈值但未配置 → 否（回退普通方式）</li>
     * </ul>
     */
    public static boolean shouldUseDatax(long estimatedRows, String dataxHome) {
        if (estimatedRows < DATAX_ROW_THRESHOLD) {
            return false;
        }
        return isDataxConfigured(dataxHome);
    }

    public static String describeDecision(long estimatedRows, boolean useDatax, boolean dataxConfigured) {
        if (estimatedRows < 0) {
            return "估算行数未知，使用普通 DML 全量";
        }
        if (estimatedRows < DATAX_ROW_THRESHOLD) {
            return "估算约 " + estimatedRows + " 行（<" + DATAX_ROW_THRESHOLD + "），使用普通 DML 全量";
        }
        if (useDatax) {
            return "估算约 " + estimatedRows + " 行（≥" + DATAX_ROW_THRESHOLD + "），使用 DataX";
        }
        if (!dataxConfigured) {
            return "估算约 " + estimatedRows + " 行（≥" + DATAX_ROW_THRESHOLD
                    + "），但未配置 DataX，回退普通 DML 全量";
        }
        return "估算约 " + estimatedRows + " 行，使用普通 DML 全量";
    }
}
