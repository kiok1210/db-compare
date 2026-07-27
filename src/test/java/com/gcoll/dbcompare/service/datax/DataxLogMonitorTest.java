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
        assertTrue(snap.shortStatus().contains("行"));
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
}
