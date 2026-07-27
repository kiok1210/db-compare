package com.gcoll.dbcompare.service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DataX 风格有界通道：读线程投递行批，写线程消费后 {@code executeBatch}。
 * 结束哨兵为 {@link #END}；写侧异常通过 {@link #fail(Throwable)} 通知读侧尽快退出。
 */
public final class SyncRecordChannel {

    /** 通道结束哨兵（身份比较，勿 put 空列表冒充） */
    public static final Object END = new Object();

    /** 默认可积压批次数（每批通常 ≤ keyset 页或 pendingLimit） */
    public static final int DEFAULT_CAPACITY = 4;

    private final BlockingQueue<Object> queue;
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    public SyncRecordChannel(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    public SyncRecordChannel() {
        this(DEFAULT_CAPACITY);
    }

    public void fail(Throwable t) {
        if (t != null) {
            error.compareAndSet(null, t);
        }
    }

    public Throwable error() {
        return error.get();
    }

    public void throwIfFailed() throws Exception {
        Throwable t = error.get();
        if (t == null) {
            return;
        }
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new Exception(t);
    }

    /**
     * 投递一批行；若写侧已失败则抛出。支持中断。
     */
    public void putBatch(List<Object[]> batch) throws InterruptedException, Exception {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        putObject(batch);
    }

    public void putEnd() throws InterruptedException, Exception {
        putObject(END);
    }

    private void putObject(Object o) throws InterruptedException, Exception {
        while (true) {
            throwIfFailed();
            if (queue.offer(o, 1, TimeUnit.SECONDS)) {
                return;
            }
            throwIfFailed();
        }
    }

    /**
     * 取下一批或 {@link #END}；写侧循环调用。
     */
    public Object take() throws InterruptedException, Exception {
        while (true) {
            throwIfFailed();
            Object o = queue.poll(1, TimeUnit.SECONDS);
            if (o != null) {
                return o;
            }
        }
    }

    /** 写侧失败后尽量清空，解除读侧阻塞 */
    public void clear() {
        queue.clear();
    }

    public int capacity() {
        return queue.remainingCapacity() + queue.size();
    }
}
