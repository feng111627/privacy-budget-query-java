package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：本地账本（预算扣减与幂等）。
 */
class BudgetLedgerTest {

    private static final QueryResult DUMMY = new QueryResult(0, 0, 0.1, 0, 1, 1, 0, "dummy");

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @Test
    @DisplayName("账本: 并发扣减不出现负余额且成功数等于预算可承受次数")
    void concurrentDeductNeverNegative() throws Exception {
        BudgetLedger ledger = new BudgetLedger();
        ledger.topUp("caller-a", 0.5); // 每次扣 0.1，最多成功 5 次

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String reqId = "req-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (ledger.tryDeduct("caller-a", reqId, 0.1, DUMMY)) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(5, success.get(), "成功扣减次数必须恰好等于预算可承受次数");
        assertEquals(0.0, ledger.balanceOf("caller-a"), 1e-9, "余额不得为负");
        log("本地账本", "并发扣减(50线程/预算0.5/单次0.1)",
                "通过: 成功5次, 余额=" + ledger.balanceOf("caller-a"));
    }

    @Test
    @DisplayName("账本: 同一 requestId 不会被重复扣减")
    void duplicateRequestNotDoubleDeducted() {
        BudgetLedger ledger = new BudgetLedger();
        ledger.topUp("caller-b", 1.0);

        assertTrue(ledger.tryDeduct("caller-b", "req-1", 0.1, DUMMY));
        assertFalse(ledger.tryDeduct("caller-b", "req-1", 0.1, DUMMY), "重复 requestId 必须拒绝");
        assertEquals(0.9, ledger.balanceOf("caller-b"), 1e-9, "只应扣减一次");
        assertTrue(ledger.findCached("caller-b", "req-1").isPresent());
        log("本地账本", "重复requestId幂等", "通过: 第二次拒绝, 余额=0.9");
    }

    @Test
    @DisplayName("账本: 余额不足时扣减失败且无副作用")
    void insufficientBalanceNoSideEffect() {
        BudgetLedger ledger = new BudgetLedger();
        ledger.topUp("caller-c", 0.05);

        assertFalse(ledger.tryDeduct("caller-c", "req-1", 0.1, DUMMY));
        assertEquals(0.05, ledger.balanceOf("caller-c"), 1e-9, "失败扣减不得改变余额");
        assertTrue(ledger.findCached("caller-c", "req-1").isEmpty(), "失败扣减不得登记幂等记录");
        log("本地账本", "余额不足扣减", "通过: 拒绝且无副作用");
    }

    @Test
    @DisplayName("账本: 充值开启新批次并清空幂等缓存")
    void topUpStartsNewBatch() {
        BudgetLedger ledger = new BudgetLedger();
        long b1 = ledger.topUp("caller-d", 0.2);
        ledger.tryDeduct("caller-d", "req-1", 0.1, DUMMY);
        long b2 = ledger.topUp("caller-d", 0.2);

        assertTrue(b2 > b1, "批次号必须递增");
        assertTrue(ledger.findCached("caller-d", "req-1").isEmpty(), "新批次应清空幂等缓存");
        log("本地账本", "充值开新批次", "通过: 批次 " + b1 + " -> " + b2);
    }
}
