package com.github.feng111627.privacybudgetqueryjava.audit;

import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志：按时间顺序记录每次成功查询的参数版本、预算批次、查询内容、
 * 预算消耗与结论，支撑历史结论的追溯与复现。
 *
 * 容量控制：保留最近 {@code capacity} 条成功查询的环形窗口（默认
 * {@value #DEFAULT_CAPACITY} 条）。超出窗口的旧条目被淘汰，但历史结论的
 * 精确复现不依赖本日志——复现所需的参数版本与授权状态由版本服务长期保存；
 * 窗口内的条目则可驱动“一键重算并比对”的完整复现。
 */
@Component
public class AuditTrail {

    public record Entry(long seq, String callerId, String requestId, String field,
                        StatisticType statistic, String groupKey, int sampleSize,
                        long policyVersion, long batchId, double epsilonConsumed,
                        double value, double errorMargin, String outcome) {
    }

    private static final int DEFAULT_CAPACITY = 10_000;

    private final AtomicLong seq = new AtomicLong();
    private final java.util.concurrent.locks.ReentrantLock lock =
            new java.util.concurrent.locks.ReentrantLock();
    private final Entry[] ring;
    private int head;
    private int size;

    public AuditTrail() {
        this(DEFAULT_CAPACITY);
    }

    public AuditTrail(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 >= 1");
        }
        this.ring = new Entry[capacity];
    }

    public void record(Entry entry) {
        lock.lock();
        try {
            ring[head] = entry;
            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 生成下一条审计记录（自动分配序号）。
     */
    public Entry next(String callerId, String requestId, String field,
                      StatisticType statistic, String groupKey, int sampleSize,
                      long policyVersion, long batchId, double epsilonConsumed,
                      double value, double errorMargin, String outcome) {
        Entry entry = new Entry(seq.incrementAndGet(), callerId, requestId, field,
                statistic, groupKey, sampleSize, policyVersion, batchId,
                epsilonConsumed, value, errorMargin, outcome);
        record(entry);
        return entry;
    }

    public List<Entry> entriesOf(String callerId) {
        return snapshot().stream().filter(e -> e.callerId().equals(callerId)).toList();
    }

    public List<Entry> all() {
        return snapshot();
    }

    /** 当前保留的审计条数（不超过容量）。 */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return ring.length;
    }

    private List<Entry> snapshot() {
        lock.lock();
        try {
            java.util.List<Entry> out = new java.util.ArrayList<>(size);
            int start = size < ring.length ? 0 : head;
            for (int i = 0; i < size; i++) {
                out.add(ring[(start + i) % ring.length]);
            }
            return List.copyOf(out);
        } finally {
            lock.unlock();
        }
    }
}
