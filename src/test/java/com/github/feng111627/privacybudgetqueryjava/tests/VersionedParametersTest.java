package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.config.QueryParameters;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：参数版本发布与历史结论复现。
 *
 * <p>注意：{@link #setUp()} 中的一次 grant 已把版本推到 baseVersion=1，
 * 该版本未发布参数，参数回落到内置默认版（与第一轮口径一致）。
 */
class VersionedParametersTest {

    private static final List<Double> ROWS =
            List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 2000.0);

    private BudgetLedger ledger;
    private FieldPolicyService policy;
    private QueryService service;
    private AuditTrail audit;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        policy = new FieldPolicyService();
        Dataset dataset = new Dataset();
        audit = new AuditTrail();
        dataset.put("metric", "g", ROWS);
        service = new QueryService(ledger, policy, dataset, audit);
        ledger.topUp("analyst", 100.0);
        policy.grant("analyst", "metric"); // baseVersion = 1，参数仍为默认
    }

    private QueryRequest sum(String reqId) {
        return new QueryRequest(reqId, "analyst", "metric", StatisticType.SUM, "g");
    }

    private RejectionReason reject(QueryRequest request) {
        return assertThrows(QueryRejectedException.class, () -> service.execute(request)).getReason();
    }

    @Test
    @DisplayName("参数版本: v0 默认参数与第一轮口径一致，未发布参数的版本回落到默认版")
    void defaultVersionV0() {
        assertEquals(0.1, policy.parametersAt(0).epsilonPerQuery(), 1e-12);
        assertEquals(5, policy.parametersAt(0).minGroupSize());
        assertEquals(1000.0, policy.parametersAt(0).maxValue(), 1e-12);
        // baseVersion=1 只有授权变更、没有参数发布 -> 参数仍是默认
        assertEquals(QueryParameters.DEFAULTS, policy.parametersAt(policy.currentVersion()));
        log("参数版本", "默认版口径/回落", "通过: ε=0.1, minGroup=5, maxValue=1000");
    }

    @Test
    @DisplayName("参数版本: 发布新版后新查询走新版，老结论按老版本原样复现")
    void publishNewVersionAndReproduceOld() {
        QueryResult oldResult = service.execute(sum("old"));
        long vOld = oldResult.policyVersion();
        long batch = oldResult.batchId();
        assertEquals(0.1, oldResult.epsilonConsumed(), 1e-12);
        double oldError = oldResult.errorMargin();

        long vNew = policy.publishParameters(0.2, 8, null); // 只改 ε 与门槛
        assertEquals(vOld + 1, vNew);

        QueryResult newResult = service.execute(sum("new"));
        assertEquals(vNew, newResult.policyVersion());
        assertEquals(0.2, newResult.epsilonConsumed(), 1e-12, "新查询必须按新版 ε 扣预算");
        assertEquals(8, policy.parametersAt(vNew).minGroupSize());
        assertEquals(1000.0, policy.parametersAt(vNew).maxValue(), 1e-12, "未覆盖项沿用上一版");
        assertTrue(newResult.errorMargin() < oldError, "ε 变大后误差范围应变小");

        QueryResult replayOld = service.replay(sum("old-replay"), vOld, batch);
        assertEquals(oldResult.value(), replayOld.value(), 1e-12, "历史结论值必须与当初完全一致");
        assertEquals(oldResult.errorMargin(), replayOld.errorMargin(), 1e-12);
        assertEquals(oldResult.epsilonConsumed(), replayOld.epsilonConsumed(), 1e-12);
        assertEquals(vOld, replayOld.policyVersion());

        log("参数版本", "新查询走新版/老结论复现",
                String.format("通过: v%d值=%.6f±%.4f复现一致; v%d ε=0.2 误差%.4f->%.4f",
                        vOld, oldResult.value(), oldError, vNew, oldError, newResult.errorMargin()));
    }

    @Test
    @DisplayName("参数版本: 历史版本参数可精确查回，未发布项沿用上一条")
    void historicalParametersLookUp() {
        long vEps = policy.publishParameters(0.3, null, null);   // 只改 ε
        long vClip = policy.publishParameters(null, null, 50.0); // 只改截断
        long vGrant = policy.grant("another", "metric");          // 授权变更不改参数

        assertEquals(0.1, policy.parametersAt(0).epsilonPerQuery(), 1e-12);
        assertEquals(0.3, policy.parametersAt(vEps).epsilonPerQuery(), 1e-12);
        assertEquals(5, policy.parametersAt(vEps).minGroupSize(), "vEps 未改门槛，沿用默认 5");
        assertEquals(0.3, policy.parametersAt(vClip).epsilonPerQuery(), 1e-12, "vClip 未改 ε，沿用 0.3");
        assertEquals(50.0, policy.parametersAt(vClip).maxValue(), 1e-12);
        QueryParameters atGrant = policy.parametersAt(vGrant);
        assertEquals(0.3, atGrant.epsilonPerQuery(), 1e-12, "授权版本的参数沿用上一条");
        assertEquals(50.0, atGrant.maxValue(), 1e-12);
        assertEquals(5, atGrant.minGroupSize());
        log("参数版本", "历史参数查回",
                String.format("通过: v%d=ε0.3, v%d=截断50, v%d授权沿用, current=v%d",
                        vEps, vClip, vGrant, policy.currentVersion()));
    }

    @Test
    @DisplayName("参数版本: 截断范围按版本生效，两版原始截断和不同、加噪结论不同")
    void clipRangeVersioned() {
        QueryResult v0Result = service.execute(sum("c0"));
        QueryResult replayV0 = service.replay(sum("c0r"), v0Result.policyVersion(), v0Result.batchId());
        assertEquals(v0Result.value(), replayV0.value(), 1e-12, "老版本结论按老截断复现");

        long vClip = policy.publishParameters(null, null, 50.0);
        QueryResult vClipResult = service.execute(sum("c1"));
        assertEquals(vClip, vClipResult.policyVersion());

        double clippedAt50 = ROWS.stream().mapToDouble(x -> Math.max(-50.0, Math.min(50.0, x))).sum();
        double clippedAt1000 = ROWS.stream().mapToDouble(x -> Math.max(-1000.0, Math.min(1000.0, x))).sum();
        assertEquals(95.0, clippedAt50, 1e-12);   // 1+...+9 + 50
        assertEquals(1045.0, clippedAt1000, 1e-12); // 1+...+9 + 1000
        assertNotEquals(v0Result.value(), vClipResult.value(), "截断边界变化后加噪结论应不同");
        log("参数版本", "截断范围版本化",
                String.format("通过: 旧截断和1045, v%d(maxValue=50)截断和95, 结论不同", vClip));
    }

    @Test
    @DisplayName("参数版本: 新版门槛生效拒绝新查询，历史版本下某调用方授权状态可查回")
    void minGroupAndGrantsAtVersion() {
        long vGrant = policy.grant("newcomer", "metric");
        long vThreshold = policy.publishParameters(null, 99, null); // 门槛抬到 99

        assertEquals(GrantState.NEVER, policy.grantStateAt("newcomer", "metric", vGrant - 1),
                "授权之前该调用方从未授权");
        assertEquals(GrantState.GRANTED, policy.grantStateAt("newcomer", "metric", vThreshold),
                "授权版本起该调用方已授权");

        ledger.topUp("newcomer", 1.0);
        assertEquals(RejectionReason.INSUFFICIENT_SAMPLE, reject(
                new QueryRequest("n1", "newcomer", "metric", StatisticType.COUNT, "g")));
        assertEquals(1.0, ledger.balanceOf("newcomer"), 1e-9, "样本不足不扣预算");

        // 按默认版本复现只读，不受新门槛影响
        QueryResult replay = service.replay(
                new QueryRequest("n0r", "newcomer", "metric", StatisticType.COUNT, "g"), 0L, 0L);
        assertEquals(ROWS.size(), replay.sampleSize());
        log("参数版本", "门槛版本化+历史授权查回",
                String.format("通过: v%d门槛99拒绝新查询不扣预算; newcomer 在 v%d 前 NEVER、起 GRANTED",
                        vThreshold, vGrant));
    }

    @Test
    @DisplayName("参数版本: 无实际变化的发布/授权/撤权不产生新版本")
    void noChangeDoesNotBumpVersion() {
        long current = policy.currentVersion();
        assertEquals(current, policy.publishParameters(0.1, 5, 1000.0), "参数相同不应升版本");
        assertEquals(current, policy.grant("analyst", "metric"), "重复授权（状态不变）不应升版本");

        long firstRevoke = policy.revoke("analyst", "metric"); // GRANTED -> REVOKED，升版
        long afterRevoke = policy.currentVersion();
        assertEquals(firstRevoke, afterRevoke);
        assertEquals(afterRevoke, policy.revoke("analyst", "metric"),
                "对已撤权的键再次撤权（状态不变）不应升版本");
        log("参数版本", "无变化不产生新版本",
                String.format("通过: 相同参数/重复授权不升版, 首次撤权->v%d, 再次撤权不升版", afterRevoke));
    }
}
