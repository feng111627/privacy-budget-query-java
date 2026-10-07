package com.github.feng111627.privacybudgetqueryjava.audit;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志：按时间顺序记录每次查询的系统版本、预算批次、当时参数、
 * 预算消耗与结论，支撑历史结论的追溯与按版本复现。
 *
 * <p>容量控制：只保留最近 {@code maxEntries} 条成功查询的审计记录。
 * 裁剪策略与 {@code SystemStateService} 的版本快照保留窗口由查询编排层
 * 联动收敛——最老一条仍保留的审计所引用的版本一定不会被裁掉，
 * 因此容量控制不会破坏保留窗口内的历史复现。</p>
 */
@Component
public class AuditTrail {

    /** 默认保留最近 10000 条成功查询的审计记录。 */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    public record Entry(long seq, String callerId, String requestId, String field,
                        StatisticType statistic, String groupKey,
                        long version, long batchId,
                        QueryParams params, double epsilonConsumed,
                        double value, int sampleSize, String outcome) {
    }

    private final int maxEntries;
    private final AtomicLong seq = new AtomicLong();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public AuditTrail() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public AuditTrail(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 必须 >= 1");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * 追加一条审计并按容量裁剪，返回被裁剪掉的最老记录数量（供上层联动裁剪快照）。
     * 裁剪后仍保留的最老条目的版本可通过 {@link #oldestRetainedVersion()} 获得。
     */
    public synchronized int append(Entry entry) {
        entries.addLast(entry);
        int pruned = 0;
        while (entries.size() > maxEntries) {
            entries.removeFirst();
            pruned++;
        }
        return pruned;
    }

    /**
     * 生成下一条审计记录（自动分配序号）并追加。
     */
    public synchronized Entry next(String callerId, String requestId, String field,
                                   StatisticType statistic, String groupKey,
                                   long version, long batchId, QueryParams params,
                                   double epsilonConsumed, double value,
                                   int sampleSize, String outcome) {
        Entry entry = new Entry(seq.incrementAndGet(), callerId, requestId, field,
                statistic, groupKey, version, batchId, params, epsilonConsumed,
                value, sampleSize, outcome);
        append(entry);
        return entry;
    }

    /** 当前保留的最老一条审计引用的系统版本；无记录时返回空。 */
    public synchronized Optional<Long> oldestRetainedVersion() {
        Entry oldest = entries.peekFirst();
        return oldest == null ? Optional.empty() : Optional.of(oldest.version());
    }

    /** 按序号精确取回一条审计记录（可能已被裁剪，返回空）。 */
    public synchronized Optional<Entry> bySeq(long seqValue) {
        for (Entry e : entries) {
            if (e.seq() == seqValue) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public synchronized List<Entry> entriesOf(String callerId) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (e.callerId().equals(callerId)) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries);
    }

    public synchronized int retainedSize() {
        return entries.size();
    }
}
