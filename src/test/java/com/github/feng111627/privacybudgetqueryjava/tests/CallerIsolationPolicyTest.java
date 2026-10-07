package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.GrantState;
import com.github.feng111627.privacybudgetqueryjava.query.Dataset;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：按调用方隔离的字段授权（撤权互不牵连；从未授权 vs 授权后撤权可区分）。
 */
class CallerIsolationPolicyTest {

    private FieldPolicyService policy;
    private QueryService service;
    private BudgetLedger ledger;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        policy = new FieldPolicyService();
        Dataset dataset = new Dataset();
        AuditTrail audit = new AuditTrail();
        dataset.put("region", "north", List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0));
        service = new QueryService(ledger, policy, dataset, audit);
        ledger.topUp("team-open", 1.0);
        ledger.topUp("team-closed", 1.0);
    }

    private QueryRequest count(String caller, String reqId) {
        return new QueryRequest(reqId, caller, "region", StatisticType.COUNT, "north");
    }

    private RejectionReason reject(QueryRequest request) {
        return assertThrows(QueryRejectedException.class, () -> service.execute(request)).getReason();
    }

    @Test
    @DisplayName("调用方隔离: 同一字段对不同调用方授权状态互不影响")
    void grantPerCallerIndependent() {
        long vGrant = policy.grant("team-open", "region");
        // team-closed 从未授权
        assertEquals(GrantState.GRANTED, policy.grantState("team-open", "region"));
        assertEquals(GrantState.NEVER, policy.grantState("team-closed", "region"));

        // 撤掉 team-open，不牵连任何其他调用方（此处 team-closed 仍为 NEVER）
        long vRevoke = policy.revoke("team-open", "region");
        assertEquals(GrantState.REVOKED, policy.grantState("team-open", "region"));
        assertEquals(GrantState.NEVER, policy.grantState("team-closed", "region"));

        // 给 team-closed 授权，team-open 仍保持撤权
        long vOther = policy.grant("team-closed", "region");
        assertEquals(GrantState.REVOKED, policy.grantState("team-open", "region"),
                "给别的调用方授权不得改变本调用方的撤权状态");
        assertTrue(policy.isAuthorized("team-closed", "region"));
        log("按调用方授权", "撤权互不牵连",
                String.format("通过: 授权v%d->撤权v%d, team-open=REVOKED, team-closed 授权v%d",
                        vGrant, vRevoke, vOther));
    }

    @Test
    @DisplayName("调用方隔离: 从未授权与授权后撤权在查询侧给出不同拒绝原因")
    void neverAuthorizedVsRevokedAreDistinct() {
        // team-closed 从未授权 -> FIELD_NEVER_AUTHORIZED
        assertEquals(RejectionReason.FIELD_NEVER_AUTHORIZED, reject(count("team-closed", "r0")));

        // team-open：授权后能查，撤权后变成 FIELD_AUTHORIZATION_REVOKED
        policy.grant("team-open", "region");
        QueryResult ok = service.execute(count("team-open", "r1"));
        assertTrue(ok.value() > 0);
        policy.revoke("team-open", "region");
        assertEquals(RejectionReason.FIELD_AUTHORIZATION_REVOKED, reject(count("team-open", "r2")));

        // 两个原因必须是不同的枚举
        assertFalse(RejectionReason.FIELD_NEVER_AUTHORIZED
                        .equals(RejectionReason.FIELD_AUTHORIZATION_REVOKED),
                "两种未授权状态必须可区分");
        // 被拒绝的查询不消耗预算
        assertEquals(1.0, ledger.balanceOf("team-closed"), 1e-9);
        assertEquals(1.0 - QueryService.EPSILON_PER_QUERY, ledger.balanceOf("team-open"), 1e-9,
                "撤权后拒绝的查询不扣预算");
        log("按调用方授权", "NEVER与REVOKED拒绝原因区分",
                "通过: NEVER->FIELD_NEVER_AUTHORIZED, 撤权->FIELD_AUTHORIZATION_REVOKED");
    }

    @Test
    @DisplayName("调用方隔离: 同一字段一个调用方被撤权时另一调用方仍可正常查询")
    void oneCallerRevokedOtherStillQueries() {
        policy.grant("team-open", "region");
        policy.grant("team-closed", "region");
        QueryResult before = service.execute(count("team-closed", "c1"));

        policy.revoke("team-open", "region"); // 只撤 team-open，版本号递增

        // team-closed 在新版本下仍可正常查询（不被牵连）
        QueryRequest currentQuery = count("team-closed", "c2");
        QueryResult after = service.execute(currentQuery);
        assertTrue(after.policyVersion() >= before.policyVersion());

        // 撤权前的旧结论按当时版本重算，必须与当初完全一致（历史复现不受新版本影响）
        QueryRequest oldQuery = count("team-closed", "c1");
        QueryResult replayOld = service.replay(oldQuery, before.policyVersion(), before.batchId());
        assertEquals(before.value(), replayOld.value(), 1e-12, "旧版本结论必须原样复现");
        assertEquals(before.errorMargin(), replayOld.errorMargin(), 1e-12);
        assertEquals(RejectionReason.FIELD_AUTHORIZATION_REVOKED, reject(count("team-open", "o1")));
        log("按调用方授权", "撤一方不影响另一方查询",
                String.format("通过: team-closed 旧版v%d值=%.6f复现一致, 新版仍可查, team-open 已拒绝",
                        before.policyVersion(), before.value()));
    }
}
