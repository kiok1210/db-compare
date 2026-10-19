package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataxLogMonitorTest {

    @Test
    void consume_progressLine_fromScreenshot() {
        String line = "2026-07-22 16:21:02.123 [job-0] INFO  StandAloneJobContainerCommunicator - "
                + "Total 4640032 records, 149639296 bytes | Speed 1.94MB/s, 65872 records/s | "
                + "Error 0 records, 0 bytes |  All Task WaitWriterTime 530.706s |  "
                + "All Task WaitReaderTime 27.691s | Percentage 0.00%";
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        assertEquals(DataxLogMonitor.Kind.PROGRESS, DataxLogMonitor.consume(line, snap));
        assertEquals(4_640_032L, snap.totalRecords);
        assertEquals(65_872L, snap.speedRecordsPerSec);
        assertEquals(0L, snap.errorRecords);
        assertTrue(snap.shortStatus().contains("行"), snap.shortStatus());
        assertTrue(snap.shortStatus().contains("1.94MB/s"), snap.shortStatus());
        assertTrue(snap.shortStatus().contains("已传"), snap.shortStatus());
        assertTrue(snap.progressDetail().contains("1.94MB/s"));
    }

    @Test
    void consume_summaryReadTotal() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        assertEquals(DataxLogMonitor.Kind.SUMMARY,
                DataxLogMonitor.consume("读出记录总数                    :           63215362", snap));
        assertEquals(63_215_362L, snap.summaryReadRecords);
        assertEquals(63_215_362L, snap.bestRowCount());
    }

    @Test
    void consume_ignoresNoise() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        assertEquals(DataxLogMonitor.Kind.IGNORE,
                DataxLogMonitor.consume("PerfTrace not enable!", snap));
        assertEquals(DataxLogMonitor.Kind.IGNORE,
                DataxLogMonitor.consume("                averageCpu                     | maxDeltaCpu", snap));
    }

    @Test
    void consume_errorLine() {
        assertEquals(DataxLogMonitor.Kind.ERROR,
                DataxLogMonitor.consume("2026-07-22 ERROR JobContainer - 某异常 Exception in thread",
                        new DataxLogMonitor.Snapshot()));
    }

    @Test
    void progress_usesSourceCountAsDenominator() {
        DataxLogMonitor.Snapshot snap = new DataxLogMonitor.Snapshot();
        snap.sourceTotalRows = 60_000_000L;
        snap.clearTargetRows = 25_000_000L;
        String line = "Total 6000000 records, 100 bytes | Speed 1.94MB/s, 50000 records/s | "
                + "Error 0 records, 0 bytes | Percentage 0.00%";
        assertEquals(DataxLogMonitor.Kind.PROGRESS, DataxLogMonitor.consume(line, snap));
        assertTrue(snap.shortStatus().contains("源"), snap.shortStatus());
        assertTrue(snap.progressPercentText().startsWith("10."), snap.progressPercentText());
        assertTrue(snap.progressDetail().contains("源COUNT"), snap.progressDetail());
    }

    @Test
    void consume_priorityNumberFormat_notError() {
        String line = "2026-08-19 17:51:06.001 [0-0-0-0] ERROR Engine - "
                + "priority set to 0, because NumberFormatException, the value is: null";
        assertEquals(DataxLogMonitor.Kind.IGNORE,
                DataxLogMonitor.consume(line, new DataxLogMonitor.Snapshot()));
        assertFalse(DataxLogMonitor.looksLikeError(line));
        assertTrue(DataxLogMonitor.isBenignDataxNoise(line));
    }
}
