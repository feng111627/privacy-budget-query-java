package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import com.github.feng111627.privacybudgetqueryjava.domain.VersionSnapshot;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.query.Dataset;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import com.github.feng111627.privacybudgetqueryjava.versioning.SystemStateService;
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
 * 业务域：参数版本发布、授权变更与查询并发时的版本一致性。
 */
class ConcurrentVersionSwitchTest {

    private static final int THREADS = 24;

    private SystemStateService state;
    private BudgetLedger ledger;
    private AuditTrail audit;
    private QueryService service;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        state = new SystemStateService();
        ledger = new BudgetLedger();
        audit = new AuditTrail();
        Dataset dataset = new Dataset();
        dataset.put("salary", "eng", List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0));
        service = new QueryService(ledger, state, dataset, audit);
        state.grant("caller-q", "salary");
        ledger.topUp("caller-q", 100.0);
    }

    @Test
    @DisplayName("并发切换: 查询与连续参数发布并发，每条成功结论内部参数/版本/批次自洽且落在单一版本")
    void queriesPinOneSnapshotWhileParamsPublish() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        Map<RejectionReason, AtomicInteger> rejected = new ConcurrentHashMap<>();
        AtomicInteger observedVersions = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.execute(new QueryRequest(
                            "race-" + idx, "caller-q", "salary", StatisticType.SUM, "eng"));
                    ok.incrementAndGet();
                } catch (QueryRejectedException e) {
                    rejected.computeIfAbsent(e.getReason(), k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 发布线程：在查询进行期间连续发布多组不同参数
        FuturePublisher publisher = new FuturePublisher(pool, go, state);
        publisher.start();

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        publisher.awaitCompletion();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.printf("[单测覆盖] 并发切换结果: 成功=%d, 拒绝=%s, 最终版本=v%d%n",
                ok.get(), rejected, state.currentVersion());

        // 核心断言：每条审计记录的 params 必须等于其 version 对应快照的 params，
        // epsilonConsumed 必须与该版本 ε 一致 —— 不允许半新半旧。
        for (AuditTrail.Entry e : audit.all()) {
            VersionSnapshot snapshot = state.snapshotAt(e.version());
            assertTrue(snapshot != null, "成功结论引用的版本必须仍可取回");
            assertEquals(snapshot.params(), e.params(),
                    "审计记录参数必须与其版本快照逐字段一致（查询完整落在单一版本）");
            assertEquals(e.params().epsilonPerQuery(), e.epsilonConsumed(), 1e-12,
                    "扣预算口径必须与判定所用版本一致");
            observedVersions.incrementAndGet();
        }

        // 预算扣减总额必须等于成功次数 × 各自版本 ε；余额绝不为负
        double charged = audit.all().stream()
                .mapToDouble(AuditTrail.Entry::epsilonConsumed).sum();
        assertEquals(100.0 - charged, ledger.balanceOf("caller-q"), 1e-9,
                "扣减总额必须与账本一致");
        assertTrue(ledger.balanceOf("caller-q") >= 0.0, "余额不得为负");
        assertEquals(ok.get(), audit.all().size(), "只有成功查询才记账");
        assertTrue(state.currentVersion() > 1, "并发期间确实发生了版本切换");
        assertTrue(ok.get() > 0, "应有查询落在旧版本、也有落在新版本");

        log("并发版本切换", "查询×24 与 参数发布×10 并发",
                String.format("通过: 成功=%d, 全部结论参数/版本自洽, 余额=%.4f 非负, 最终版本=v%d",
                        ok.get(), ledger.balanceOf("caller-q"), state.currentVersion()));
    }

    @Test
    @DisplayName("并发切换: 查询与按调用方授权/撤权并发，拒绝原因正确且调用方互不牵连")
    void queriesRacingWithPerCallerRevocation() throws Exception {
        state.grant("caller-b", "salary");
        ledger.topUp("caller-b", 100.0);
        // A 初始从未授权，但要有充足预算，确保本用例观察到的拒绝只来自授权窗口
        ledger.topUp("caller-a", 100.0);

        int perCaller = 16;
        ExecutorService pool = Executors.newFixedThreadPool(perCaller * 2 + 1);
        CountDownLatch ready = new CountDownLatch(perCaller * 2);
        CountDownLatch go = new CountDownLatch(1);
        Map<RejectionReason, AtomicInteger> rejectedA = new ConcurrentHashMap<>();
        Map<RejectionReason, AtomicInteger> rejectedB = new ConcurrentHashMap<>();
        AtomicInteger okA = new AtomicInteger();
        AtomicInteger okB = new AtomicInteger();

        for (int i = 0; i < perCaller; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    Thread.sleep((long) (idx * 5L)); // 摊开到 从未授权/已授权/已撤权 三个窗口
                    service.execute(new QueryRequest(
                            "a-" + idx, "caller-a", "salary", StatisticType.SUM, "eng"));
                    okA.incrementAndGet();
                } catch (QueryRejectedException e) {
                    rejectedA.computeIfAbsent(e.getReason(), k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.execute(new QueryRequest(
                            "b-" + idx, "caller-b", "salary", StatisticType.SUM, "eng"));
                    okB.incrementAndGet();
                } catch (QueryRejectedException e) {
                    rejectedB.computeIfAbsent(e.getReason(), k -> new AtomicInteger()).incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 与查询并发：A 经历 从未授权 -> 授权 -> 撤权；B 始终授权
        FuturePublisher publisher = new FuturePublisher(pool, go, state);
        publisher.startAuthChurn("caller-a");

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        publisher.awaitCompletion();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.printf("[单测覆盖] 并发授权切换: A 成功=%d 拒绝=%s | B 成功=%d 拒绝=%s%n",
                okA.get(), rejectedA, okB.get(), rejectedB);

        // A 的拒绝只可能是 NEVER_GRANTED（授权前）或 FIELD_NOT_AUTHORIZED（撤权后），两种原因各自合法
        rejectedA.keySet().forEach(r ->
                assertTrue(r == RejectionReason.FIELD_NEVER_GRANTED
                                || r == RejectionReason.FIELD_NOT_AUTHORIZED,
                        "A 的拒绝原因只能是从未授权或已撤权: " + r));
        // B 从未被撤权：不允许出现任何授权类拒绝，更不能被 A 的撤权牵连
        assertEquals(0,
                rejectedB.getOrDefault(RejectionReason.FIELD_NOT_AUTHORIZED, new AtomicInteger()).get()
                        + rejectedB.getOrDefault(RejectionReason.FIELD_NEVER_GRANTED, new AtomicInteger()).get(),
                "B 始终授权，不得受 A 的授权/撤权影响");
        assertEquals(perCaller, okB.get(), "B 的全部查询都应成功");
        assertEquals(AuthState.GRANTED, state.authState("caller-b", "salary"), "B 当前仍授权");
        assertTrue(okA.get() + count(rejectedA) == perCaller, "A 成功+拒绝总数必须等于请求数");

        log("并发版本切换", "A授权抖动/B恒授权 并发查询",
                String.format("通过: A 成功=%d 拒绝=%s; B 成功=%d 无授权拒绝(互不牵连)",
                        okA.get(), rejectedA.keySet(), okB.get()));
    }

    private static int count(Map<RejectionReason, AtomicInteger> map) {
        return map.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** 封装发布任务，确保发布动作发生在 go 释放之后，并用 Future 等待其真正结束。 */
    private static final class FuturePublisher {
        private final ExecutorService pool;
        private final CountDownLatch go;
        private final SystemStateService state;
        private java.util.concurrent.Future<?> future;

        FuturePublisher(ExecutorService pool, CountDownLatch go, SystemStateService state) {
            this.pool = pool;
            this.go = go;
            this.state = state;
        }

        void start() {
            future = pool.submit(() -> {
                try {
                    go.await();
                    double[] eps = {0.1, 0.2, 0.3, 0.15, 0.25, 0.4, 0.5, 0.12, 0.33, 0.08};
                    for (double e : eps) {
                        state.publishParams(new QueryParams(e, 5, 1000.0));
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void startAuthChurn(String caller) {
            future = pool.submit(() -> {
                try {
                    go.await();
                    // 留出一段时间让部分查询先看到 NEVER_GRANTED，再授权
                    Thread.sleep(20);
                    state.grant(caller, "salary");
                    // 授权窗口：让部分查询在 GRANTED 下成功
                    Thread.sleep(40);
                    state.revoke(caller, "salary");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void awaitCompletion() throws Exception {
            // 用 Future.get() 等任务结束；池化工作线程任务完成后仍存活，绝不能 Thread.join。
            future.get(10, TimeUnit.SECONDS);
        }
    }
}
