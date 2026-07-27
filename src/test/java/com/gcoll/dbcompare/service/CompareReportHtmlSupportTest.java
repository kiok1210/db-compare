package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareReportHtmlSupportTest {

    @Test
    void enhance_injectsNavAndForceExtract() {
        String html = "<!DOCTYPE html><html><head><title>r</title></head><body>"
                + "<div>report</div>"
                + "<script>function openDimModal(type){}</script>"
                + "</body></html>";

        String out = CompareReportHtmlSupport.enhance(html, 42L);

        assertTrue(out.contains("data-dbcompare-nav=\"1\""));
        assertTrue(out.contains("数据库比对工具"));
        assertTrue(out.contains("/sync/list"));
        assertTrue(out.contains("data-dbcompare-extract-force=\"1\""));
        assertTrue(out.contains("COMPARE_TASK_ID = 42"));
        assertTrue(out.contains("location.href"));
        assertTrue(out.contains("/sync/from-compare/"));
        assertTrue(out.contains("addEventListener('click', onDocClick, true)"));
        assertTrue(out.contains("wireExtractButton"));
        assertTrue(out.contains("pathHasExtract"));
        assertFalse(out.contains("while (toolbar.firstChild)"));
        assertTrue(out.contains("data-dbcompare-modal-fix=\"1\""));
        assertTrue(out.contains("pointer-events:auto!important"));
        assertTrue(out.contains("data-dbcompare-row-diff=\"1\""));
        assertTrue(out.contains("/compare/row-diff/"));
        assertTrue(out.contains("showCountRowDiff"));
        assertTrue(out.contains("查看差异数据"));
        assertTrue(out.contains("data-dbcompare-reexec=\"1\""));
        assertTrue(out.contains("/compare/reExecute/"));
        assertTrue(out.contains("reExecuteCompareFromReport"));
        assertTrue(out.contains("重新执行"));
    }

    @Test
    void enhance_replacesForceScriptAndStaysSingle() {
        String html = "<html><body><script>function extractCountDiffSync(){}</script></body></html>";
        String once = CompareReportHtmlSupport.enhance(html, 7L);
        String twice = CompareReportHtmlSupport.enhance(once, 99L);
        assertTrue(once.contains("COMPARE_TASK_ID = 7"));
        assertTrue(twice.contains("COMPARE_TASK_ID = 99"));
        assertFalse(twice.contains("COMPARE_TASK_ID = 7"));
        assertEquals(twice.indexOf("data-dbcompare-extract-force=\"1\""),
                twice.lastIndexOf("data-dbcompare-extract-force=\"1\""));
    }
}
