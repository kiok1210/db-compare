package com.gcoll.dbcompare.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务名称生成：避免「重新执行」后缀层层叠加。
 */
public final class TaskNameHelper {

    private static final int MAX_LEN = 200;
    private static final DateTimeFormatter REEXEC_TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 匹配末尾「 - 重新执行」及可选时间戳 / #序号 */
    private static final String REEXEC_SUFFIX =
            "(?i)\\s*-\\s*重新执行(\\s*#\\d+|\\s+\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2})?\\s*$";

    private TaskNameHelper() {
    }

    /**
     * 去掉已叠加的「重新执行」后缀，得到原始基名。
     */
    public static String stripReExecuteSuffix(String name) {
        if (name == null) {
            return "";
        }
        String base = name.trim();
        while (true) {
            String next = base.replaceFirst(REEXEC_SUFFIX, "").trim();
            if (next.equals(base)) {
                break;
            }
            base = next;
        }
        return base;
    }

    /**
     * 重新执行任务名：基名 + 「 - 重新执行 MM-dd HH:mm」（不叠加旧后缀）。
     */
    public static String buildReExecuteName(String originalName) {
        return buildReExecuteName(originalName, LocalDateTime.now());
    }

    static String buildReExecuteName(String originalName, LocalDateTime at) {
        String base = stripReExecuteSuffix(originalName);
        if (base.isEmpty()) {
            base = "未命名任务";
        }
        String name = base + " - 重新执行 " + at.format(REEXEC_TS);
        return truncate(name, MAX_LEN);
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
