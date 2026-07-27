package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportHtmlEncodingTest {

    @Test
    void decodeHtmlBytes_recoversGbkLegacyReport() {
        String original = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>比对报告</title></head>"
                + "<body><div>总表数</div><div>条数差异</div></body></html>";
        byte[] gbkBytes = original.getBytes(Charset.forName("GBK"));
        String decoded = ReportHtmlEncoding.decodeHtmlBytes(gbkBytes);
        assertTrue(decoded.contains("条数差异"));
        assertTrue(decoded.contains("总表数"));
        assertTrue(decoded.contains("比对报告"));
    }

    @Test
    void decodeHtmlBytes_keepsUtf8() {
        String original = "<html><body>条数差异 UTF-8</body></html>";
        String decoded = ReportHtmlEncoding.decodeHtmlBytes(original.getBytes(StandardCharsets.UTF_8));
        assertTrue(decoded.contains("条数差异 UTF-8"));
    }
}
