package com.gcoll.dbcompare.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 报告 HTML 编解码：写入统一 UTF-8；读取时兼容历史上 FileWriter（Windows 默认 GBK）落盘的文件。
 */
public final class ReportHtmlEncoding {

    private static final Charset GBK = Charset.forName("GBK");

    private ReportHtmlEncoding() {
    }

    public static String readHtml(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return decodeHtmlBytes(bytes);
    }

    public static String decodeHtmlBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (looksHealthy(utf8)) {
            return utf8;
        }
        String gbk = new String(bytes, GBK);
        if (scoreChinese(gbk) > scoreChinese(utf8)) {
            return gbk;
        }
        return utf8;
    }

    private static boolean looksHealthy(String html) {
        if (html.indexOf('\uFFFD') >= 0) {
            return false;
        }
        // 常见中文标签能对上，说明 UTF-8 解码正确
        return html.contains("条数") || html.contains("总表") || html.contains("比对报告")
                || html.contains("一致") || html.contains("charset");
    }

    private static int scoreChinese(String s) {
        int score = 0;
        if (s.indexOf('\uFFFD') >= 0) {
            score -= 1000;
        }
        String[] keys = {"条数", "总表", "比对", "一致", "差异", "提取", "同步", "源库", "目标库"};
        for (String k : keys) {
            if (s.contains(k)) {
                score += 10;
            }
        }
        return score;
    }
}
