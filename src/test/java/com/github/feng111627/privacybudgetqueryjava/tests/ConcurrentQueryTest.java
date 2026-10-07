package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import com.github.feng111627.privacybudgetqueryjava.query.Dataset;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：并发查询的端到端一致性。
 */
class ConcurrentQueryTest {

    private BudgetLedger ledger;
    private QueryService service;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        FieldPolicyService policy = new FieldPolicyService();
        Dataset dataset = new Dataset();
        AuditTrail audit = new AuditTrail();
        dataset.put("salary", "eng", List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0));
        policy.grant("caller-x", "salary");
        policy.grant("caller-y", "salary");
        service = new QueryService(ledger, policy, dataset, audit);
    }

    private Map<RejectionReason, AtomicInteger> runConcurrent(int threads,
                                                              java.util.function.IntFunction<QueryRequest> factory)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        Map<RejectionReason, AtomicInteger> rejections = new ConcurrentHashMap<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.execute(factory.apply(idx));
                    ok.incrementAndGet();
                } catch (QueryRejectedException e) {
                    rejections.computeIfAbsent(e.getReason(), k -> new AtomicInteger())
                              .incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        System.out.printf("[单测覆盖] 并发结果: 成功=%d, 拒绝分布=%s%n", ok.get(), rejections);
        return rejections;
    }

    @Test
    @DisplayName("并发: 不同 requestId 并发查询，成功数恰为预算可承受次数且余额不为负")
    void concurrentDistinctRequests() throws InterruptedException {
        ledger.topUp("caller-x", 0.5); // 最多 5 次成功
        int threads = 40;
        Map<RejectionReason, AtomicInteger> rejections = runConcurrent(threads,
                i -> new QueryRequest("req-" + i, "caller-x", "salary", StatisticType.SUM, "eng"));

        int exhausted = rejections.getOrDefault(RejectionReason.BUDGET_EXHAUSTED, new AtomicInteger()).get();
        assertEquals(threads - 5, exhausted, "其余请求必须因预算耗尽被拒绝");
        assertEquals(0.0, ledger.balanceOf("caller-x"), 1e-9, "余额不得为负");
        log("并发查询", "40线程抢0.5预算", "通过: 成功5次, 拒绝35次(BUDGET_EXHAUSTED), 余额=0");
    }

    @Test
    @DisplayName("并发: 同一 requestId 并发提交，最多成功一次且只扣一次预算")
    void concurrentDuplicateRequestId() throws InterruptedException {
        ledger.topUp("caller-y", 1.0);
        int threads = 20;
        Map<RejectionReason, AtomicInteger> rejections = runConcurrent(threads,
                i -> new QueryRequest("same-req", "caller-y", "salary", StatisticType.SUM, "eng"));

        int duplicates = rejections.getOrDefault(RejectionReason.DUPLICATE_REQUEST, new AtomicInteger()).get();
        assertEquals(threads - 1, duplicates, "除一次成功外其余必须判为重复提交");
        assertEquals(1.0 - QueryService.EPSILON_PER_QUERY, ledger.balanceOf("caller-y"), 1e-9,
                "同一请求只扣一次预算");
        log("并发查询", "20线程同requestId", "通过: 成功1次, 重复拒绝19次, 只扣一次预算");
    }
}
