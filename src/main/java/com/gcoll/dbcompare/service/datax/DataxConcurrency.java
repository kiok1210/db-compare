package com.gcoll.dbcompare.service.datax;

/**
 * DataX 进程并发门闩与通道数换算，避免表级并行时同时拉起过多 DataX JVM 撑爆内存。
 */
public final class DataxConcurrency {

    /** 默认同时最多 1 个 DataX 进程 */
    public static final int DEFAULT_MAX_CONCURRENT = 1;
    /** 允许配置的上限 */
    public static final int ABS_MAX_CONCURRENT = 3;
    /** 所有并发 DataX 作业合计通道预算（再按进程数均分） */
    public static final int GLOBAL_CHANNEL_BUDGET = 8;
    /** 单作业通道默认 / 上限 */
    public static final int DEFAULT_CHANNEL = 4;
    public static final int ABS_MAX_CHANNEL = 16;

    private final Object lock = new Object();
    private int active;

    public DataxConcurrency() {
    }

    /**
     * 占用一个 DataX 槽位；已满则阻塞等待。
     *
     * @param maxConcurrent 1–{@link #ABS_MAX_CONCURRENT}
     * @param cancelCheck   返回 true 时放弃等待并抛 InterruptedException
     * @param waitHeartbeat 每次 wait 轮询调用（看门狗/UI 心跳；可为 null）
     */
    public void acquire(int maxConcurrent, java.util.function.BooleanSupplier cancelCheck,
                        Runnable waitHeartbeat)
            throws InterruptedException {
        int max = clampConcurrent(maxConcurrent);
        synchronized (lock) {
            while (active >= max) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    throw new InterruptedException("等待 DataX 槽位时任务已取消");
                }
                if (waitHeartbeat != null) {
                    try {
                        waitHeartbeat.run();
                    } catch (Exception ignored) {
                        // 心跳失败不影响排队
                    }
                }
                lock.wait(400L);
            }
            active++;
        }
    }

    /** @deprecated 请传 waitHeartbeat，避免排队期间无心跳被看门狗误杀 */
    @Deprecated
    public void acquire(int maxConcurrent, java.util.function.BooleanSupplier cancelCheck)
            throws InterruptedException {
        acquire(maxConcurrent, cancelCheck, null);
    }

    public void release() {
        synchronized (lock) {
            if (active > 0) {
                active--;
            }
            lock.notifyAll();
        }
    }

    public int getActive() {
        synchronized (lock) {
            return active;
        }
    }

    public static int clampConcurrent(int v) {
        if (v < 1 || v > ABS_MAX_CONCURRENT) {
            return DEFAULT_MAX_CONCURRENT;
        }
        return v;
    }

    public static int clampChannel(int v) {
        if (v < 1 || v > ABS_MAX_CHANNEL) {
            return DEFAULT_CHANNEL;
        }
        return v;
    }

    /**
     * 按「同时允许的 DataX 进程数」均分全局通道预算，再与配置通道取较小值。
     * 例：maxConcurrent=1, channel=8 → 8；maxConcurrent=2, channel=8 → 4。
     */
    public static int effectiveChannel(int configuredChannel, int maxConcurrent) {
        int ch = clampChannel(configuredChannel);
        int max = clampConcurrent(maxConcurrent);
        int perJob = Math.max(1, GLOBAL_CHANNEL_BUDGET / max);
        return Math.min(ch, perJob);
    }
}
