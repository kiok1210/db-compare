package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProgressTextHelperTest {

    @Test
    void parallelWithDetailKeepsShort() {
        Set<String> running = new LinkedHashSet<>();
        running.add("#1/18 gcoll_acs.logrecord → gcoll_acs.logrecord");
        running.add("#2/18 gcoll_acs.person_aud → gcoll_acs.person_aud");
        running.add("#3/18 gcoll_acs.revinfo → gcoll_acs.revinfo");
        running.add("#4/18 gcoll_aux.customer_payments → gcoll_aux.customer_payments");
        running.add("#5/18 gcoll_aux.orders → gcoll_aux.orders");
        running.add("#6/18 gcoll_aux.items → gcoll_aux.items");
        running.add("#7/18 gcoll_aux.users → gcoll_aux.users");
        running.add("#8/18 gcoll_aux.audit → gcoll_aux.audit");

        String ui = ProgressTextHelper.build(
                running, "#3/18 gcoll_acs.revinfo → gcoll_acs.revinfo · 写入 12000 行");
        assertTrue(ui.length() <= ProgressTextHelper.MAX_LEN, ui);
        assertTrue(ui.startsWith("并行×8 · #3/18 revinfo"), ui);
        assertFalse(ui.contains("|"), ui);
    }

    @Test
    void parallelListShowsAtMostTwoPlusRest() {
        Set<String> running = new LinkedHashSet<>();
        running.add("#1/9 PUBLIC.ACCESS_LOGS → PUBLIC.ACCESS_LOGS");
        running.add("#3/9 PUBLIC.USERS → PUBLIC.USERS");
        running.add("#4/9 SHOP.AUDIT_TRAIL → SHOP.AUDIT_TRAIL");

        String ui = ProgressTextHelper.build(running, "并行×3");
        assertEquals("并行×3: #1/9 ACCESS_LOGS, #3/9 USERS 等1张", ui);
        assertTrue(ui.length() <= ProgressTextHelper.MAX_LEN);
    }

    @Test
    void abbreviateNeverExceedsMax() {
        StringBuilder sb = new StringBuilder(500);
        for (int i = 0; i < 500; i++) sb.append('x');
        String longName = sb.toString();
        assertEquals(200, ProgressTextHelper.abbreviate(longName, 200).length());
        assertTrue(ProgressTextHelper.abbreviate(longName, 200).endsWith("..."));
    }

    @Test
    void emptyRunningUsesDetailOnly() {
        assertEquals("完成", ProgressTextHelper.build(null, "完成"));
        assertEquals("完成", ProgressTextHelper.build(java.util.Collections.emptySet(), "完成"));
    }

    @Test
    void formatRowsPerSec_showsAverageAndRecent() {
        assertEquals("均速 2000 行/秒, 近段 900 行/秒",
                ProgressTextHelper.formatRowsPerSec(10_000_000, 5000, 4500, 5));
        assertEquals("均速 100 行/秒",
                ProgressTextHelper.formatRowsPerSec(100, 1, -1, 0));
    }
}
