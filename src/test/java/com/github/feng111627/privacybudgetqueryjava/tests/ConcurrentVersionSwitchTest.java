package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：参数版本发布 / 授权变更 / 查询并发发生时的版本一致性。
 */
class ConcurrentVersionSwitchTest {

    private BudgetLedger ledger;
    private FieldPolicyService policy;
    private Dataset dataset;
    private AuditTrail audit;
    private QueryService service;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        policy = new FieldPolicyService();
        dataset = new Dataset();
        audit = new AuditTrail();
        dataset.put("m", "g", List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0));
        service = new QueryService(ledger, policy, dataset, audit);
    }

    private QueryRequest req(String caller, String reqId) {
        return new QueryRequest(reqId, caller, "m", StatisticType.SUM, "g");
    }

    /**
     * 用审计条目驱动重放：每条成功结论按其记录的（版本, 批次）重算，
     * 值与误差必须与当初完全一致——证明整条查询当时落在同一个版本上。
     * 同时校验各条 ε 与其记录版本的参数严格一致。
     */
    private void assertEveryEntryReproducesExactly(String caller) {
        for (AuditTrail.Entry e : audit.entriesOf(caller)) {
            QueryRequest original = new QueryRequest(e.requestId(), e.callerId(), e.field(),
                    e.statistic(), e.groupKey());
            QueryResult replay = service.replay(original, e.policyVersion(), e.batchId());
            assertEquals(e.value(), replay.value(), 1e-12,
                    "审计结论必须能按记录版本/批次精确复现, requestId=" + e.requestId());
            assertEquals(e.errorMargin(), replay.errorMargin(), 1e-12);
            assertEquals(policy.parametersAt(e.policyVersion()).epsilonPerQuery(),
                    e.epsilonConsumed(), 1e-12, "扣减 ε 必须与记录版本一致");
        }
    }

    @Test
    @DisplayName("并发切换: 查询与参数发布同时进行，每条结论落在单一版本且预算守恒不为负")
    void queriesVsConcurrentPublishes() throws Exception {
        int callers = 3;
        int perCallerThreads = 60;
        double topUp = 1.5; // 每个调用方充值
        for (int c = 0; c < callers; c++) {
            String caller = "p" + c;
            ledger.topUp(caller, topUp);
            policy.grant(caller, "m");
        }

        int threads = callers * perCallerThreads;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean stopPublishing = new AtomicBoolean(false);
        AtomicInteger ok = new AtomicInteger();

        // 发布者：持续发布递增的 ε（0.1,0.2,0.3 循环），与查询并发交错
        pool.submit(() -> {
            ready.countDown();
            double[] eps = {0.1, 0.2, 0.3};
            int i = 0;
            try {
                ready.await();
                go.await();
                while (!stopPublishing.get()) {
                    policy.publishParameters(eps[i % eps.length], null, null);
                    i++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int c = 0; c < callers; c++) {
            final String caller = "p" + c;
            for (int i = 0; i < perCallerThreads; i++) {
                final String reqId = caller + "-r" + i;
                ready.countDown();
                futures.add(pool.submit(() -> {
                    try {
                        go.await();
                        service.execute(req(caller, reqId));
                        ok.incrementAndGet();
                    } catch (QueryRejectedException e) {
                        // BUDGET_EXHAUSTED / DUPLICATE_REQUEST 允许，其它不允许
                        assertTrue(e.getReason() == RejectionReason.BUDGET_EXHAUSTED
                                        || e.getReason() == RejectionReason.DUPLICATE_REQUEST,
                                "并发发布期间不应出现授权/样本类拒绝: " + e.getReason());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        stopPublishing.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 每个调用方：成功结论的 ε 之和 + 剩余预算 == 充值额（预算守恒、不为负）
        for (int c = 0; c < callers; c++) {
            String caller = "p" + c;
            double spent = audit.entriesOf(caller).stream()
                    .mapToDouble(AuditTrail.Entry::epsilonConsumed).sum();
            double remaining = ledger.balanceOf(caller);
            assertEquals(topUp, spent + remaining, 1e-9,
                    caller + " 预算必须守恒: 充值=" + topUp + " 已花=" + spent + " 剩余=" + remaining);
            assertTrue(remaining >= 0.0, caller + " 余额不得为负");
            assertEveryEntryReproducesExactly(caller);
        }
        log("并发切换", "查询×并发参数发布(3调用方×60线程)",
                String.format("通过: 成功%d条, 全部可按记录版本精确复现, 各调用方预算守恒不为负", ok.get()));
    }

    @Test
    @DisplayName("并发切换: 撤权与查询并发，结论按版本二值一致，撤权只影响目标调用方")
    void queriesVsConcurrentRevoke() throws Exception {
        ledger.topUp("open", 10.0);
        ledger.topUp("other", 10.0);
        policy.grant("open", "m");
        policy.grant("other", "m");

        int threads = 80;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okOpen = new AtomicInteger();
        AtomicInteger revokedOpen = new AtomicInteger();
        AtomicInteger okOther = new AtomicInteger();
        CountDownLatch firstOpenSucceeded = new CountDownLatch(1);

        // 撤权者：等第一个 open 查询在授权版本上落地后再撤，保证撤权点两侧都有查询，
        // 撤权动作仍与后续查询并发交错。
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                assertTrue(firstOpenSucceeded.await(10, TimeUnit.SECONDS));
                policy.revoke("open", "m");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean openSide = (i % 2 == 0);
            final String caller = openSide ? "open" : "other";
            final String reqId = caller + "-r" + i;
            ready.countDown();
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                    service.execute(req(caller, reqId));
                    if (openSide) {
                        okOpen.incrementAndGet();
                        firstOpenSucceeded.countDown();
                    } else {
                        okOther.incrementAndGet();
                    }
                } catch (QueryRejectedException e) {
                    if (openSide) {
                        assertEquals(RejectionReason.FIELD_AUTHORIZATION_REVOKED, e.getReason(),
                                "open 侧只可能因撤权被拒（从未授权不成立）");
                        revokedOpen.incrementAndGet();
                    } else {
                        throw new AssertionError("other 侧不受撤权影响，不应被拒: " + e.getReason());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // open 侧：撤权点把查询切成两段，成功 + 撤权拒绝 = 总请求，互不矛盾
        assertEquals(threads / 2, okOpen.get() + revokedOpen.get(), "open 侧每个请求要么成功要么撤权拒绝");
        assertTrue(okOpen.get() >= 1, "撤权前应有查询落在授权版本上");
        assertEquals(threads / 2, okOther.get(), "other 侧全部成功，撤权不牵连");

        // 每条 open 成功结论按记录版本重放，且记录版本必然是授权状态
        assertEveryEntryReproducesExactly("open");
        for (AuditTrail.Entry e : audit.entriesOf("open")) {
            assertTrue(policy.isAuthorizedAt("open", "m", e.policyVersion()),
                    "成功结论的记录版本必然是授权状态");
        }
        assertEveryEntryReproducesExactly("other");

        log("并发切换", "撤权×查询并发(80线程/两个调用方)",
                String.format("通过: open 成功%d+撤权拒绝%d, other 成功%d不受影响, 成功结论均可按版本复现",
                        okOpen.get(), revokedOpen.get(), okOther.get()));
    }
}
