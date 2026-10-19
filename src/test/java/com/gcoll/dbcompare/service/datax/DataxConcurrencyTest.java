package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxConcurrencyTest {

    @Test
    void effectiveChannel_serialKeepsConfiguredUpToBudget() {
        assertEquals(4, DataxConcurrency.effectiveChannel(4, 1));
        assertEquals(8, DataxConcurrency.effectiveChannel(8, 1));
        assertEquals(8, DataxConcurrency.effectiveChannel(16, 1)); // 受全局预算 8 限制
    }

    @Test
    void effectiveChannel_splitsBudgetAcrossConcurrent() {
        assertEquals(4, DataxConcurrency.effectiveChannel(8, 2));
        assertEquals(2, DataxConcurrency.effectiveChannel(8, 3));
        assertEquals(1, DataxConcurrency.effectiveChannel(1, 3));
    }

    @Test
    void acquire_blocksUntilRelease() throws Exception {
        DataxConcurrency gate = new DataxConcurrency();
        gate.acquire(1, () -> false);
        assertEquals(1, gate.getActive());

        AtomicBoolean entered = new AtomicBoolean(false);
        AtomicInteger started = new AtomicInteger(0);
        Thread waiter = new Thread(() -> {
            try {
                started.incrementAndGet();
                gate.acquire(1, () -> false);
                entered.set(true);
                gate.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(300);
        assertEquals(1, started.get());
        assertTrue(!entered.get());
        gate.release();
        waiter.join(3000);
        assertTrue(entered.get());
        assertEquals(0, gate.getActive());
    }

    @Test
    void acquire_waitHeartbeat_firesWhileBlocked() throws Exception {
        DataxConcurrency gate = new DataxConcurrency();
        gate.acquire(1, () -> false, null);
        AtomicInteger beats = new AtomicInteger(0);
        Thread waiter = new Thread(() -> {
            try {
                gate.acquire(1, () -> false, beats::incrementAndGet);
                gate.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(1200);
        assertTrue(beats.get() >= 1, "waiting acquire should heartbeat");
        gate.release();
        waiter.join(3000);
        assertEquals(0, gate.getActive());
    }

    @Test
    void acquire_cancelWhileWaiting_throws() throws Exception {
        DataxConcurrency gate = new DataxConcurrency();
        gate.acquire(1, () -> false);
        assertThrows(InterruptedException.class, () -> gate.acquire(1, () -> true));
        gate.release();
    }
}
