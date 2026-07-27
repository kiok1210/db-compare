package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.DatabaseType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 同步空闲看门狗：按「无消息/无进度」判定卡死。
 * <ul>
 *   <li>普通表：超过 {@link #DEFAULT_TABLE_IDLE_MS} 无活动 → 跳过该表</li>
 *   <li>GoldenDB 目标：{@link #forTarget} 使用 {@link #SLOW_TARGET_TABLE_IDLE_MS}</li>
 *   <li>超大表（{@link #markLargeTable}）：使用 {@link #LARGE_TABLE_IDLE_MS}</li>
 *   <li>整任务：默认 {@link #DEFAULT_TASK_IDLE_MS}；存在超大表时用 {@link #LARGE_TASK_IDLE_MS}</li>
 * </ul>
 * 正常运行时应持续 {@link #touch}（由进度/心跳日志驱动），超时只表示「长时间无心跳」。
 */
public final class SyncIdleWatchdog {

    /** 普通表：90 秒无心跳则跳过（内网/等池时 30s 易误杀；有心跳则不会拖死） */
    public static final long DEFAULT_TABLE_IDLE_MS = 90_000L;
    /** 普通任务：3 分钟无心跳则整任务失败 */
    public static final long DEFAULT_TASK_IDLE_MS = 180_000L;
    /** GoldenDB/慢目标：单表空闲上限 */
    public static final long SLOW_TARGET_TABLE_IDLE_MS = 180_000L;
    /** GoldenDB/慢目标：整任务空闲上限 */
    public static final long SLOW_TARGET_TASK_IDLE_MS = 600_000L;
    /** 超大表（约 ≥100 万行）单表空闲上限 */
    public static final long LARGE_TABLE_IDLE_MS = 300_000L;
    /** 存在超大表时的整任务空闲上限 */
    public static final long LARGE_TASK_IDLE_MS = 600_000L;
    /** 判定超大表的行数阈值（估算或已写入行数） */
    public static final long LARGE_TABLE_ROW_THRESHOLD = 1_000_000L;
    /**
     * 差异同步（双端比对）更易在打开游标/排序时长时间无进度：
     * 估算行数 ≥ 此值即按超大表放宽看门狗（仍需心跳）。
     */
    public static final long DATA_SYNC_HEAVY_ROW_THRESHOLD = 50_000L;
    /**
     * 全量/传输 INSERT：估算 ≥ 此值启用 DataX 式「读→有界通道→写」流式写入（读写并行）。
     */
    public static final long STREAM_CHANNEL_ROW_THRESHOLD = 100_000L;
    /** GoldenDB 建议的表级并行上限，降低协议异常与等连接无心跳 */
    public static final int GOLDENDB_MAX_PARALLEL = 6;

    private final long tableIdleMs;
    private final long taskIdleMs;
    private final AtomicLong lastTaskActivityMs = new AtomicLong(System.currentTimeMillis());
    private final Map<String, AtomicLong> tableActivity = new ConcurrentHashMap<>();
    private final Map<String, Long> tableIdleOverrideMs = new ConcurrentHashMap<>();
    private final Map<String, Runnable> forceCloseByTable = new ConcurrentHashMap<>();
    private final Set<String> timedOutTables = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> tableTimedOutAtMs = new ConcurrentHashMap<>();
    private final AtomicBoolean taskTimedOut = new AtomicBoolean(false);
    private final AtomicLong taskTimedOutAtMs = new AtomicLong(0);
    private final AtomicBoolean largeTableMode = new AtomicBoolean(false);

    public SyncIdleWatchdog() {
        this(DEFAULT_TABLE_IDLE_MS, DEFAULT_TASK_IDLE_MS);
    }

    /** 按目标库类型选择更合适的空闲阈值（GoldenDB 内网易慢）。 */
    public static SyncIdleWatchdog forTarget(DatabaseType targetDbType) {
        if (targetDbType == DatabaseType.GOLDENDB) {
            return new SyncIdleWatchdog(SLOW_TARGET_TABLE_IDLE_MS, SLOW_TARGET_TASK_IDLE_MS);
        }
        return new SyncIdleWatchdog();
    }

    public SyncIdleWatchdog(long tableIdleMs, long taskIdleMs) {
        this.tableIdleMs = Math.max(1L, tableIdleMs);
        this.taskIdleMs = Math.max(this.tableIdleMs, taskIdleMs);
    }

    /** 本实例配置的普通表空闲阈值（不含超大表覆盖）。 */
    public long configuredTableIdleMs() {
        return tableIdleMs;
    }

    /** 本实例配置的整任务空闲阈值（不含超大表模式抬升）。 */
    public long configuredTaskIdleMs() {
        return taskIdleMs;
    }

    public void touch() {
        touch(null);
    }

    public void touch(String tableTag) {
        long now = System.currentTimeMillis();
        lastTaskActivityMs.set(now);
        if (tableTag != null && !tableTag.isEmpty()) {
            tableActivity.computeIfAbsent(tableTag, k -> new AtomicLong(now)).set(now);
        }
    }

    /**
     * 将该表标为超大表：单表看门狗放宽到 {@link #LARGE_TABLE_IDLE_MS}，
     * 并启用整任务 {@link #LARGE_TASK_IDLE_MS}。
     * @return true 表示本次新标记
     */
    public boolean markLargeTable(String tableTag) {
        if (tableTag == null || tableTag.isEmpty()) {
            return false;
        }
        Long prev = tableIdleOverrideMs.put(tableTag, LARGE_TABLE_IDLE_MS);
        largeTableMode.set(true);
        touch(tableTag);
        return prev == null || prev.longValue() != LARGE_TABLE_IDLE_MS;
    }

    public boolean isLargeTable(String tableTag) {
        return tableTag != null && tableIdleOverrideMs.containsKey(tableTag);
    }

    public boolean isLargeTableMode() {
        return largeTableMode.get();
    }

    public long tableIdleLimitMs(String tableTag) {
        if (tableTag != null) {
            Long o = tableIdleOverrideMs.get(tableTag);
            if (o != null) {
                return o;
            }
        }
        return tableIdleMs;
    }

    public long taskIdleLimitMs() {
        return largeTableMode.get() ? Math.max(taskIdleMs, LARGE_TASK_IDLE_MS) : taskIdleMs;
    }

    public void register(String tableTag, Runnable forceClose) {
        if (tableTag == null || tableTag.isEmpty()) {
            return;
        }
        touch(tableTag);
        if (forceClose != null) {
            forceCloseByTable.put(tableTag, forceClose);
        }
    }

    public void unregister(String tableTag) {
        if (tableTag == null) {
            return;
        }
        tableActivity.remove(tableTag);
        forceCloseByTable.remove(tableTag);
        // 保留 override / largeTableMode，避免并行超大表刚结束又把整任务时限缩回去
    }

    public boolean isTableTimedOut(String tableTag) {
        return tableTag != null && timedOutTables.contains(tableTag);
    }

    public boolean isTaskTimedOut() {
        return taskTimedOut.get();
    }

    public long lastTaskActivityMs() {
        return lastTaskActivityMs.get();
    }

    /** 表被判定超时的时刻；未超时返回 0 */
    public long tableTimedOutAtMs(String tableTag) {
        if (tableTag == null) {
            return 0L;
        }
        Long t = tableTimedOutAtMs.get(tableTag);
        return t != null ? t : 0L;
    }

    /** 整任务被判定超时的时刻；未超时返回 0 */
    public long taskTimedOutAtMs() {
        return taskTimedOutAtMs.get();
    }

    /**
     * 扫描空闲表：新超时的表会加入 timedOut 集合并执行 forceClose。
     * @return 本次新判定超时的表标签
     */
    public List<String> abortIdleTables(long nowMs, Consumer<String> onTableTimeout) {
        List<String> newly = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : tableActivity.entrySet()) {
            String tag = e.getKey();
            if (timedOutTables.contains(tag)) {
                continue;
            }
            long last = e.getValue().get();
            if (nowMs - last < tableIdleLimitMs(tag)) {
                continue;
            }
            if (timedOutTables.add(tag)) {
                newly.add(tag);
                tableTimedOutAtMs.put(tag, nowMs);
                forceCloseQuietly(tag);
                if (onTableTimeout != null) {
                    onTableTimeout.accept(tag);
                }
            }
        }
        return newly;
    }

    /**
     * 若整任务空闲超时，标记并强制关闭所有仍注册的表连接。
     * @return true 表示本次新触发任务超时
     */
    public boolean abortIdleTask(long nowMs, Runnable onTaskTimeout) {
        if (taskTimedOut.get()) {
            return false;
        }
        if (nowMs - lastTaskActivityMs.get() < taskIdleLimitMs()) {
            return false;
        }
        if (!taskTimedOut.compareAndSet(false, true)) {
            return false;
        }
        taskTimedOutAtMs.set(nowMs);
        for (String tag : new ArrayList<>(forceCloseByTable.keySet())) {
            timedOutTables.add(tag);
            tableTimedOutAtMs.putIfAbsent(tag, nowMs);
            forceCloseQuietly(tag);
        }
        if (onTaskTimeout != null) {
            onTaskTimeout.run();
        }
        return true;
    }

    private void forceCloseQuietly(String tag) {
        Runnable r = forceCloseByTable.get(tag);
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }
}
