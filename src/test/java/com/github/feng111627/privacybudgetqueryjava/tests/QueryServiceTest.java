package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.query.Dataset;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import com.github.feng111627.privacybudgetqueryjava.versioning.SystemStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：统计查询编排（校验、幂等、按调用方授权、最小分组、噪声、预算、版本）。
 */
class QueryServiceTest {

    private static final String CALLER = "caller-1";

    private BudgetLedger ledger;
    private SystemStateService state;
    private Dataset dataset;
    private AuditTrail audit;
    private QueryService service;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        state = new SystemStateService();
        dataset = new Dataset();
        audit = new AuditTrail();
        service = new QueryService(ledger, state, dataset, audit);

        dataset.put("salary", "eng", List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0));
        dataset.put("salary", "tiny", List.of(1.0, 2.0));
        state.grant(CALLER, "salary");
    }

    private QueryRequest req(String caller, String reqId, String group) {
        return new QueryRequest(reqId, caller, "salary", StatisticType.SUM, group);
    }

    private RejectionReason reasonOf(QueryRequest request) {
        QueryRejectedException ex = assertThrows(QueryRejectedException.class,
                () -> service.execute(request));
        return ex.getReason();
    }

    @Test
    @DisplayName("查询: 参数非法被拒绝且原因可区分")
    void invalidParameterRejected() {
        RejectionReason reason = reasonOf(
                new QueryRequest(null, CALLER, "salary", StatisticType.SUM, "eng"));
        assertEquals(RejectionReason.INVALID_PARAMETER, reason);
        log("统计查询", "参数非法", "通过: 原因=" + reason);
    }

    @Test
    @DisplayName("查询: 重复提交被拒绝且不重复扣预算")
    void duplicateRequestRejected() {
        ledger.topUp(CALLER, 1.0);
        QueryResult first = service.execute(req(CALLER, "r1", "eng"));

        RejectionReason reason = reasonOf(req(CALLER, "r1", "eng"));
        assertEquals(RejectionReason.DUPLICATE_REQUEST, reason);
        assertEquals(1.0 - first.epsilonConsumed(), ledger.balanceOf(CALLER), 1e-9,
                "重复提交不得再次扣减预算");
        log("统计查询", "重复提交", "通过: 原因=" + reason + ", 余额=" + ledger.balanceOf(CALLER)
                + ", 首次值=" + first.value());
    }

    @Test
    @DisplayName("查询: 预算耗尽被拒绝且原因可区分（默认 ε=0.1，2 次后耗尽）")
    void budgetExhaustedRejected() {
        ledger.topUp(CALLER, 0.2); // 只够 2 次
        service.execute(req(CALLER, "r1", "eng"));
        service.execute(req(CALLER, "r2", "eng"));

        RejectionReason reason = reasonOf(req(CALLER, "r3", "eng"));
        assertEquals(RejectionReason.BUDGET_EXHAUSTED, reason);
        assertEquals(0.0, ledger.balanceOf(CALLER), 1e-9);
        log("统计查询", "预算耗尽", "通过: 原因=" + reason + ", 余额=" + ledger.balanceOf(CALLER));
    }

    @Test
    @DisplayName("查询: 样本量不足的分组被拒绝且不扣预算")
    void insufficientSampleRejected() {
        ledger.topUp(CALLER, 1.0);
        RejectionReason reason = reasonOf(req(CALLER, "r1", "tiny"));

        assertEquals(RejectionReason.INSUFFICIENT_SAMPLE, reason);
        assertEquals(1.0, ledger.balanceOf(CALLER), 1e-9, "样本不足不得扣减预算");
        log("统计查询", "样本不足(2<5)", "通过: 原因=" + reason + ", 预算未扣减");
    }

    @Test
    @DisplayName("查询: 调用方从未授权的字段被拒绝，原因 FIELD_NEVER_GRANTED")
    void neverGrantedFieldRejected() {
        ledger.topUp(CALLER, 1.0);
        RejectionReason reason = reasonOf(
                new QueryRequest("r1", CALLER, "secret", StatisticType.COUNT, null));
        assertEquals(RejectionReason.FIELD_NEVER_GRANTED, reason);
        assertEquals(1.0, ledger.balanceOf(CALLER), 1e-9, "被拒绝请求不得扣预算");
        log("统计查询", "从未授权字段", "通过: 原因=" + reason);
    }

    @Test
    @DisplayName("查询: 授权后撤权被拒绝，原因 FIELD_NOT_AUTHORIZED，与从未授权区分")
    void revokedFieldRejected() {
        ledger.topUp(CALLER, 1.0);
        service.execute(req(CALLER, "r1", "eng"));
        state.revoke(CALLER, "salary");

        RejectionReason reason = reasonOf(req(CALLER, "r2", "eng"));
        assertEquals(RejectionReason.FIELD_NOT_AUTHORIZED, reason);
        log("统计查询", "授权后撤权", "通过: 原因=" + reason + "（区别于 FIELD_NEVER_GRANTED）");
    }

    @Test
    @DisplayName("查询: 同批次内相同查询结论稳定可复现，且带误差说明")
    void reproducibleWithinBatch() {
        ledger.topUp(CALLER, 1.0);
        QueryResult a = service.execute(req(CALLER, "r1", "eng"));
        QueryResult b = service.execute(req(CALLER, "r2", "eng")); // 不同 requestId，相同查询

        assertEquals(a.value(), b.value(), 1e-12, "同批次相同查询结论必须一致");
        assertEquals(a.batchId(), b.batchId());
        assertEquals(a.version(), b.version());
        assertTrue(a.errorMargin() > 0, "必须带可解释的误差范围");
        assertTrue(a.explanation().contains("ε="), "必须带预算消耗说明");
        log("统计查询", "同批次可复现", "通过: 两次值=" + a.value() + ", 版本=v" + a.version()
                + ", 误差±" + a.errorMargin());
    }

    @Test
    @DisplayName("查询: 撤权后拒绝，历史结论仍可按当时版本追溯授权状态")
    void revocationBlocksAndHistoryTraceable() {
        ledger.topUp(CALLER, 1.0);
        QueryResult before = service.execute(req(CALLER, "r1", "eng"));
        long versionAtQuery = before.version();

        state.revoke(CALLER, "salary");
        RejectionReason reason = reasonOf(req(CALLER, "r2", "eng"));
        assertEquals(RejectionReason.FIELD_NOT_AUTHORIZED, reason, "撤权后必须拒绝");

        assertEquals(com.github.feng111627.privacybudgetqueryjava.domain.AuthState.GRANTED,
                state.authStateAt(CALLER, "salary", versionAtQuery),
                "历史版本下该调用方当时是授权的，结论可追溯复现");
        assertEquals(versionAtQuery, audit.entriesOf(CALLER).get(0).version(),
                "审计必须记录当时生效的系统版本");
        log("统计查询", "撤权与版本追溯", "通过: 撤权后拒绝, 历史版本v" + versionAtQuery + "为GRANTED可复现");
    }

    @Test
    @DisplayName("查询: 新预算批次改变噪声种子，批次号可追溯")
    void newBatchChangesSeed() {
        ledger.topUp(CALLER, 1.0);
        QueryResult a = service.execute(req(CALLER, "r1", "eng"));

        ledger.topUp(CALLER, 1.0); // 新批次
        QueryResult b = service.execute(req(CALLER, "r1", "eng")); // 新批次下 requestId 可复用

        assertNotEquals(a.batchId(), b.batchId(), "批次号必须变化");
        log("统计查询", "新批次噪声种子", "通过: 批次 " + a.batchId() + " -> " + b.batchId()
                + ", 值 " + a.value() + " -> " + b.value());
    }
}
