package com.gcoll.dbcompare.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 同步/比对进度文案：控制长度，避免把并行表名全量拼进 CURRENT_TABLE。
 */
public final class ProgressTextHelper {

    /** 与库列兼容的硬上限（旧库曾为 VARCHAR(200)） */
    public static final int MAX_LEN = 200;

    private ProgressTextHelper() {
    }

    /**
     * 组装进度展示。并行时只保留摘要，不把全部表名用 | 拼接。
     */
    public static String build(Set<String> runningTags, String detail) {
        if (runningTags == null || runningTags.isEmpty()) {
            return abbreviate(detail, MAX_LEN);
        }
        List<String> list = new ArrayList<>(runningTags);
        Collections.sort(list);
        int n = list.size();
        String head = "并行×" + n;

        if (detail != null && !detail.isEmpty() && !detail.startsWith("并行×")) {
            return abbreviate(head + " · " + shortenDetail(detail), MAX_LEN);
        }

        StringBuilder sb = new StringBuilder(head).append(": ");
        int show = Math.min(2, n);
        for (int i = 0; i < show; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortenTag(list.get(i)));
        }
        if (n > show) {
            sb.append(" 等").append(n - show).append("张");
        }
        return abbreviate(sb.toString(), MAX_LEN);
    }

    /** 去掉「源 → 目标」重复库名，只保留序号与表名 */
    public static String shortenTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        String s = tag.trim();
        int arrow = s.indexOf(" → ");
        if (arrow < 0) {
            arrow = s.indexOf("->");
        }
        String left = arrow > 0 ? s.substring(0, arrow).trim() : s;
        // "#3/18 SCHEMA.TABLE" → "#3/18 TABLE"
        int space = left.indexOf(' ');
        if (space > 0 && left.charAt(0) == '#') {
            String idx = left.substring(0, space);
            String name = left.substring(space + 1).trim();
            return idx + " " + pureTable(name);
        }
        return pureTable(left);
    }

    static String shortenDetail(String detail) {
        if (detail == null) {
            return "";
        }
        // "#1/9 A.T → B.T · 写入 100 行" → "#1/9 T · 写入 100 行"
        int sep = detail.indexOf(" · ");
        if (sep > 0) {
            return shortenTag(detail.substring(0, sep)) + detail.substring(sep);
        }
        return shortenTag(detail);
    }

    static String pureTable(String catalogOrTable) {
        if (catalogOrTable == null) {
            return "";
        }
        String s = catalogOrTable.trim();
        int colon = s.lastIndexOf("::");
        if (colon >= 0) {
            return s.substring(colon + 2);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            return s.substring(dot + 1);
        }
        return s;
    }

    public static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max < 4) {
            return text.length() <= max ? text : text.substring(0, max);
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }

    /**
     * 同步吞吐文案：均速=累计行/累计秒；近段=本心跳窗口增量（更能反映「越写越慢」）。
     */
    public static String formatRowsPerSec(long totalRows, long elapsedSec,
                                          long deltaRows, long windowSec) {
        long avg = totalRows / Math.max(1L, elapsedSec);
        if (deltaRows < 0 || windowSec <= 0) {
            return "均速 " + avg + " 行/秒";
        }
        long recent = deltaRows / Math.max(1L, windowSec);
        return "均速 " + avg + " 行/秒, 近段 " + recent + " 行/秒";
    }
}
