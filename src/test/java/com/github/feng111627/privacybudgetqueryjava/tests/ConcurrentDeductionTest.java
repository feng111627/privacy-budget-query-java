package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.budget.BudgetModel;
import com.github.feng111627.privacybudgetqueryjava.model.MetricType;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import com.github.feng111627.privacybudgetqueryjava.service.RejectedQueryException;
import com.github.feng111627.privacybudgetqueryjava.tests.support.PrivacyTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：并发查询下预算扣减必须原子。
 * 自定义预算模型总 ε=0.5（COUNT 单价 0.1），40 个线程同时提交，
 * 期望恰好 5 个成功、35 个 BUDGET_EXHAUSTED，且余额非负、无重复扣减。
 */
class ConcurrentDeductionTest {

    private static final Logger log = Logger.getLogger(ConcurrentDeductionTest.class.getName());

    @Test
    void concurrentRequestsNeverOverrunOrDoubleCharge() throws Exception {
        PrivacyTestKit kit = new PrivacyTestKit();
        // 发布一个小预算模型：总 ε=0.5，每个 COUNT 0.1 → 恰好 5 个名额。
        // 先发布小预算模型再登记调用方，使首个批次按总 ε=0.5 开设（COUNT 单价 0.1 → 恰好 5 个名额）。
        String batch = kit.publishModelThenRegister("carol", new BudgetModel(
                "budget-concurrent", Instant.now(), 0.5d, 5, 0d, 1_000_000d,
                0.95d, 0.10d, 0.20d), "region");

        int threads = 40;
        log.info("【并发扣减】用例: " + threads
                + " 个线程并发 COUNT（总预算0.5ε、单价0.1ε）→ 期望成功=5、耗尽=35、余额≥0");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        ConcurrentLinkedQueue<Double> charges = new ConcurrentLinkedQueue<>();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    QueryRequest req = new QueryRequest("carol", "c-req-" + idx, batch,
                            MetricType.COUNT, "region", null);
                    QueryResponse resp = kit.service.submit(req);
                    success.incrementAndGet();
                    charges.add(resp.epsilonCharged());
                } catch (RejectedQueryException e) {
                    if (e.code() == RejectionCode.BUDGET_EXHAUSTED) {
                        exhausted.incrementAndGet();
                    } else {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在限定时间内完成");
        pool.shutdownNow();

        assertEquals(0, errors.size(),
                "并发执行不应出现预算耗尽以外的异常: " + errors);
        assertEquals(5, success.get(), "成功扣减次数必须恰好等于预算名额");
        assertEquals(35, exhausted.get(), "其余请求必须因预算耗尽被拒绝");

        double totalCharged = charges.stream().mapToDouble(Double::doubleValue).sum();
        double remaining = kit.service.snapshot("carol", batch).remainingBudget();
        double used = kit.service.snapshot("carol", batch).usedBudget();
        log.info(String.format(
                "  成功=%d 耗尽=%d 实际累计扣减=%.2fε 已用=%.2fε 剩余=%.2fε",
                success.get(), exhausted.get(), totalCharged, used, remaining));

        assertEquals(0.50d, totalCharged, 1e-9, "累计扣减必须恰好等于总预算");
        assertEquals(0.50d, used, 1e-9);
        assertEquals(0.0d, remaining, 1e-9, "余额绝不能为负");
        // 每个 requestId 只能扣一次：成功的 5 个请求在流水里各自只出现一次。
        long acceptedRecords = kit.service.history("carol").stream()
                .filter(r -> r.accepted() && r.epsilonCharged() > 0).count();
        assertEquals(5, acceptedRecords, "不允许同一请求被重复扣减");
        log.info("【并发扣减】结果: 无超扣、无重复扣、余额=0、流水一致 ✔");
    }
}
