package com.gcoll.dbcompare.compare;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateComparatorResultTest {

    @Test
    void getDiffSummary_whenConsistent() {
        AggregateComparator.AggregateCompareResult result = new AggregateComparator.AggregateCompareResult();
        result.setConsistent(true);

        assertEquals("一致", result.getDiffSummary());
    }

    @Test
    void getDiffSummary_whenConsistentButHashSkipped() {
        AggregateComparator.AggregateCompareResult result = new AggregateComparator.AggregateCompareResult();
        result.setConsistent(true);
        result.setRowHashSkipped(true);

        assertEquals("一致(超大表已跳过Hash)", result.getDiffSummary());
    }

    @Test
    void shouldSkipRowHash_whenEitherSideHuge() {
        assertTrue(AggregateComparator.shouldSkipRowHash(
                AggregateComparator.SKIP_ROW_HASH_ROW_THRESHOLD, 0));
        assertTrue(AggregateComparator.shouldSkipRowHash(
                0, AggregateComparator.SKIP_ROW_HASH_ROW_THRESHOLD));
        assertTrue(!AggregateComparator.shouldSkipRowHash(
                AggregateComparator.SKIP_ROW_HASH_ROW_THRESHOLD - 1, 100));
    }

    @Test
    void getDiffSummary_whenCountDiff() {
        AggregateComparator.AggregateCompareResult result = new AggregateComparator.AggregateCompareResult();
        result.setConsistent(false);
        result.setCountConsistent(false);
        result.setSourceCount(100);
        result.setTargetCount(90);
        result.setCountDiff(10);
        result.setAmountSumConsistent(true);
        result.setRowHashConsistent(true);

        String summary = result.getDiffSummary();

        assertTrue(summary.contains("条数不一致"));
        assertTrue(summary.contains("100"));
        assertTrue(summary.contains("90"));
    }

    @Test
    void getDiffSummary_whenAmountDiff() {
        AggregateComparator.AggregateCompareResult result = new AggregateComparator.AggregateCompareResult();
        result.setConsistent(false);
        result.setCountConsistent(true);
        result.setAmountSumConsistent(false);
        result.setAmountDiffFields(Arrays.asList("total_amount"));
        result.setRowHashConsistent(true);

        assertTrue(result.getDiffSummary().contains("金额不一致"));
        assertTrue(result.getDiffSummary().contains("total_amount"));
    }
}
