package com.github.feng111627.privacybudgetqueryjava.ledger;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地账本：管理每个调用方的隐私预算余额、预算批次与幂等记录。
 *
 * 一致性保证：
 * - 每个调用方一把锁，扣减与幂等登记在同一临界区内完成，
 *   并发下不会扣成负数，也不会对同一请求重复扣减。
 * - 每次充值开启新的预算批次，批次号用于噪声种子，保证同批次内结论可复现。
 *
 * 容量控制：每个调用方的幂等缓存为有界 LRU（默认保留最近
 * {@value #DEFAULT_MAX_IDEMPOTENCY_ENTRIES} 条），长期运行不会无限堆积；
 * 充值开新批次时整批清空。被淘汰的历史 requestId 不再享受重复提交保护，
 * 这属于明确的容量取舍（与审计/版本快照的保留窗口策略一致）。
 */
@Component
public class BudgetLedger {

    private static final double EPSILON_TOLERANCE = 1e-9;

    /** 每个调用方幂等缓存默认容量。 */
    public static final int DEFAULT_MAX_IDEMPOTENCY_ENTRIES = 4_096;

    private final int maxIdempotencyEntries;

    private static final class Account {
        double balance;
        long batch;
        final Map<String, QueryResult> idempotencyCache;

        Account(int maxEntries) {
            this.idempotencyCache = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, QueryResult> eldest) {
                    return size() > maxEntries;
                }
            };
        }
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public BudgetLedger() {
        this(DEFAULT_MAX_IDEMPOTENCY_ENTRIES);
    }

    public BudgetLedger(int maxIdempotencyEntries) {
        if (maxIdempotencyEntries < 1) {
            throw new IllegalArgumentException("maxIdempotencyEntries 必须 >= 1");
        }
        this.maxIdempotencyEntries = maxIdempotencyEntries;
    }

    private Account accountOf(String callerId) {
        return accounts.computeIfAbsent(callerId, k -> new Account(maxIdempotencyEntries));
    }

    /**
     * 为调用方充值预算并开启新批次，返回新的批次号。
     */
    public long topUp(String callerId, double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("topUp epsilon must be positive");
        }
        Account account = accountOf(callerId);
        synchronized (account) {
            account.balance += epsilon;
            account.batch += 1;
            // 新批次的噪声种子不同，旧批次的幂等缓存不再适用
            account.idempotencyCache.clear();
            return account.batch;
        }
    }

    /**
     * 查询当前剩余预算。
     */
    public double balanceOf(String callerId) {
        Account account = accountOf(callerId);
        synchronized (account) {
            return account.balance;
        }
    }

    /**
     * 查询当前预算批次号。
     */
    public long currentBatch(String callerId) {
        Account account = accountOf(callerId);
        synchronized (account) {
            return account.batch;
        }
    }

    /**
     * 查找调用方在当前批次下已完成的幂等结果（按 requestId）。
     */
    public Optional<QueryResult> findCached(String callerId, String requestId) {
        Account account = accountOf(callerId);
        synchronized (account) {
            return Optional.ofNullable(account.idempotencyCache.get(requestId));
        }
    }

    /**
     * 原子地尝试扣减预算并登记幂等结果。
     *
     * 余额不足时返回 false，不扣减、不登记，不产生任何副作用；
     * 同一 requestId 已存在时返回 false（由调用方走幂等返回路径）。
     */
    public boolean tryDeduct(String callerId, String requestId, double epsilon, QueryResult result) {
        Account account = accountOf(callerId);
        synchronized (account) {
            if (account.idempotencyCache.containsKey(requestId)) {
                return false;
            }
            if (account.balance + EPSILON_TOLERANCE < epsilon) {
                return false;
            }
            account.balance -= epsilon;
            if (Math.abs(account.balance) < EPSILON_TOLERANCE) {
                account.balance = 0.0;
            }
            account.idempotencyCache.put(requestId, result);
            return true;
        }
    }
}
