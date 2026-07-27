package com.gcoll.dbcompare.service.datax;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 DataX 控制台日志，用于进度监控与看门狗心跳（而非盲等进程）。
 * <p>
 * 典型进度行（StandAloneJobContainerCommunicator）：
 * {@code Total 4640032 records, 149639296 bytes | Speed 1.94MB/s, 65872 records/s | Error 0 records, 0 bytes | ... | Percentage 0.00%}
 * <p>
 * 任务结束摘要（JobContainer）：
 * {@code 读出记录总数                    :           63215362}
 */
public final class DataxLogMonitor {

    private static final Pattern PROGRESS = Pattern.compile(
            "Total\\s+(\\d+)\\s+records,\\s+(\\d+)\\s+bytes\\s*\\|\\s*Speed\\s+([^,|]+),\\s*(\\d+)\\s+records/s"
                    + ".*?Error\\s+(\\d+)\\s+records.*?Percentage\\s+([\\d.]+)%",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SUMMARY_READ = Pattern.compile(
            "读出记录总数\\s*[:：]\\s*(\\d+)");
    private static final Pattern SUMMARY_FAIL = Pattern.compile(
            "读写失败总数\\s*[:：]\\s*(\\d+)");
    private static final Pattern SUMMARY_SPEED = Pattern.compile(
            "记录写入速度\\s*[:：]\\s*(\\d+)\\s*rec/s", Pattern.CASE_INSENSITIVE);

    public enum Kind {
        /** 周期性 Total … Percentage 进度 */
        PROGRESS,
        /** 任务结束中文摘要行 */
        SUMMARY,
        /** 错误/异常 */
        ERROR,
        /** 值得展示的 INFO（启动/完成提示） */
        INFO,
        /** 噪音，不进 UI */
        IGNORE
    }

    /** 可变快照：多行日志累积最新状态 */
    public static final class Snapshot {
        public long totalRecords;
        public long totalBytes;
        public long speedRecordsPerSec;
        public String speedText = "";
        public long errorRecords;
        public double percentage;
        public Long summaryReadRecords;
        public Long summaryFailRecords;
        public Long summaryWriteSpeed;
        public boolean sawProgress;
        public boolean sawSummary;

        /** UI 短文案 */
        public String shortStatus() {
            if (summaryReadRecords != null) {
                return "完成 " + formatCount(summaryReadRecords)
                        + (summaryWriteSpeed != null ? (" · " + summaryWriteSpeed + " 行/秒") : "");
            }
            if (!sawProgress) {
                return "运行中";
            }
            String pct = percentage > 0.009 ? String.format("%.1f%%", percentage) : "进行中";
            return formatCount(totalRecords) + " 行 · " + speedRecordsPerSec + " 行/秒 · " + pct;
        }

        /** 进度日志详情 */
        public String progressDetail() {
            return "已传输 " + formatCount(totalRecords) + " 行 ("
                    + formatBytes(totalBytes) + "), 速度 "
                    + (speedText != null && !speedText.isEmpty() ? speedText.trim() : "?")
                    + " / " + speedRecordsPerSec + " 行/秒, 错误 "
                    + errorRecords + ", 进度 " + String.format("%.2f", percentage) + "%";
        }

        /** 优先摘要行数，否则最后一次进度行数 */
        public long bestRowCount() {
            if (summaryReadRecords != null && summaryReadRecords >= 0) {
                return summaryReadRecords;
            }
            if (sawProgress) {
                return totalRecords;
            }
            return -1L;
        }
    }

    private DataxLogMonitor() {
    }

    /**
     * 消费一行 DataX stdout，更新快照并返回展示类别。
     */
    public static Kind consume(String rawLine, Snapshot snap) {
        if (rawLine == null) {
            return Kind.IGNORE;
        }
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return Kind.IGNORE;
        }

        // 必须先匹配进度：行内含 "Error 0 records" 指标，不能当 ERROR
        Matcher pm = PROGRESS.matcher(line);
        if (pm.find() && snap != null) {
            snap.totalRecords = parseLong(pm.group(1));
            snap.totalBytes = parseLong(pm.group(2));
            snap.speedText = pm.group(3).trim();
            snap.speedRecordsPerSec = parseLong(pm.group(4));
            snap.errorRecords = parseLong(pm.group(5));
            snap.percentage = parseDouble(pm.group(6));
            snap.sawProgress = true;
            return Kind.PROGRESS;
        }

        if (looksLikeError(line)) {
            return Kind.ERROR;
        }

        // 摘要可能拆成多行，逐行匹配
        boolean summaryHit = false;
        Matcher rm = SUMMARY_READ.matcher(line);
        if (rm.find() && snap != null) {
            snap.summaryReadRecords = parseLong(rm.group(1));
            snap.sawSummary = true;
            summaryHit = true;
        }
        Matcher fm = SUMMARY_FAIL.matcher(line);
        if (fm.find() && snap != null) {
            snap.summaryFailRecords = parseLong(fm.group(1));
            snap.sawSummary = true;
            summaryHit = true;
        }
        Matcher sm = SUMMARY_SPEED.matcher(line);
        if (sm.find() && snap != null) {
            snap.summaryWriteSpeed = parseLong(sm.group(1));
            snap.sawSummary = true;
            summaryHit = true;
        }
        if (summaryHit) {
            return Kind.SUMMARY;
        }

        if (line.contains("StandAloneJobContainerCommunicator")
                || line.contains("JobContainer -")
                || line.contains("任务启动时刻")
                || line.contains("任务结束时刻")
                || line.contains("任务总计耗时")
                || line.contains("任务平均流量")) {
            return Kind.INFO;
        }

        // 常见噪音：PerfTrace / JVM / 插件 debug
        if (line.contains("PerfTrace")
                || line.contains("averageCpu")
                || line.contains("totalGCCount")
                || line.contains("DEBUG")
                || line.contains("hogging")
                || line.startsWith("\t")
                || line.contains("| totalGCCount")) {
            return Kind.IGNORE;
        }

        // 其余 INFO 默认忽略，避免刷屏；关键启动仍可能被 INFO 规则命中
        if (line.contains(" INFO ") || line.contains(" WARN ")) {
            return Kind.IGNORE;
        }
        return Kind.IGNORE;
    }

    /** logger 级别 ERROR / 异常；进度行已在上方优先解析 */
    static boolean looksLikeError(String line) {
        // "2026... ERROR JobContainer - msg" 或 "... ERROR  c.a... -"
        if (line.matches("(?i).*\\bERROR\\b\\s+[\\w.$]+\\s+-\\s+.*")) {
            return true;
        }
        String u = line.toUpperCase();
        return u.contains("EXCEPTION")
                || (line.contains("失败") && !line.contains("读写失败总数"));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0d;
        }
    }

    static String formatCount(long n) {
        if (n >= 100_000_000L) {
            return String.format("%.1f亿", n / 100_000_000d);
        }
        if (n >= 10_000L) {
            return String.format("%.1f万", n / 10_000d);
        }
        return String.valueOf(n);
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format("%.2fGB", bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format("%.2fMB", bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format("%.1fKB", bytes / 1024d);
        }
        return bytes + "B";
    }
}
