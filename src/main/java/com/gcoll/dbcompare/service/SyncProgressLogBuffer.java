package com.gcoll.dbcompare.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步任务进度日志（内存环形缓冲），供进度页实时拉取。
 * <p>
 * 按 taskId 保有上限，任务结束后可 {@link #remove}；避免长期运行 Map 膨胀。
 */
@Component
public class SyncProgressLogBuffer {

    private static final int MAX_PER_TASK = 400;
    /** 同时保有的任务日志上限（超限淘汰最旧） */
    static final int MAX_TASKS = 24;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConcurrentHashMap<Long, TaskLog> byTask = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastAccessMs = new ConcurrentHashMap<>();

    public void clear(Long taskId) {
        if (taskId == null) {
            return;
        }
        byTask.put(taskId, new TaskLog());
        touch(taskId);
        evictIfNeeded();
    }

    /** 任务结束后移除，释放内存 */
    public void remove(Long taskId) {
        if (taskId == null) {
            return;
        }
        byTask.remove(taskId);
        lastAccessMs.remove(taskId);
    }

    public void append(Long taskId, String type, String message) {
        if (taskId == null || message == null || message.isEmpty()) {
            return;
        }
        TaskLog log = byTask.computeIfAbsent(taskId, id -> new TaskLog());
        touch(taskId);
        log.add(type != null ? type : "info", message);
        evictIfNeeded();
    }

    /**
     * @param sinceSeq 已收到的最大序号（不含）；传 0/负数则返回全部保留日志
     */
    public List<Map<String, Object>> since(Long taskId, long sinceSeq) {
        TaskLog log = byTask.get(taskId);
        if (log == null) {
            return new ArrayList<>();
        }
        touch(taskId);
        return log.since(sinceSeq);
    }

    int taskCount() {
        return byTask.size();
    }

    private void touch(Long taskId) {
        lastAccessMs.put(taskId, System.currentTimeMillis());
    }

    private void evictIfNeeded() {
        while (byTask.size() > MAX_TASKS) {
            Long oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<Long, Long> e : lastAccessMs.entrySet()) {
                if (e.getValue() < oldestTs && byTask.containsKey(e.getKey())) {
                    oldestTs = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            remove(oldest);
        }
    }

    private static final class TaskLog {
        private final AtomicLong seq = new AtomicLong(0);
        private final List<Map<String, Object>> lines = new ArrayList<>();

        synchronized void add(String type, String message) {
            long id = seq.incrementAndGet();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", id);
            row.put("type", type);
            row.put("ts", LocalDateTime.now().format(TS));
            // 截断超长行，避免单条日志撑大堆
            String msg = message.length() > 2000 ? message.substring(0, 2000) + "…" : message;
            row.put("msg", msg);
            lines.add(row);
            while (lines.size() > MAX_PER_TASK) {
                lines.remove(0);
            }
        }

        synchronized List<Map<String, Object>> since(long sinceSeq) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : lines) {
                long s = ((Number) row.get("seq")).longValue();
                if (s > sinceSeq) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
