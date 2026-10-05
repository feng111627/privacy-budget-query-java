package com.github.feng111627.privacybudgetqueryjava.audit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志：按时间顺序记录每次查询的规则版本、预算批次、
 * 预算消耗与结论，支撑历史结论的追溯与复现。
 */
@Component
public class AuditTrail {

    public record Entry(long seq, String callerId, String requestId, String field,
                        long policyVersion, long batchId, double epsilonConsumed,
                        double value, String outcome) {
    }

    private final AtomicLong seq = new AtomicLong();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public void record(Entry entry) {
        entries.add(entry);
    }

    /**
     * 生成下一条审计记录（自动分配序号）。
     */
    public Entry next(String callerId, String requestId, String field,
                      long policyVersion, long batchId, double epsilonConsumed,
                      double value, String outcome) {
        Entry entry = new Entry(seq.incrementAndGet(), callerId, requestId, field,
                policyVersion, batchId, epsilonConsumed, value, outcome);
        record(entry);
        return entry;
    }

    public List<Entry> entriesOf(String callerId) {
        return entries.stream().filter(e -> e.callerId().equals(callerId)).toList();
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }
}
