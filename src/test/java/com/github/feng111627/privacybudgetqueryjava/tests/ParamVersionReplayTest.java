package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.ReplayResult;
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
 * 业务域：参数版本发布后，新查询走新版本、老结论按历史版本精确复现。
 */
class ParamVersionReplayTest {

    private static final String CALLER = "caller-a";

    private BudgetLedger ledger;
    private SystemStateService state;
    private AuditTrail audit;
    private QueryService service;

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @BeforeEach
    void setUp() {
        ledger = new BudgetLedger();
        state = new SystemStateService();
        audit = new AuditTrail();
        Dataset dataset = new Dataset();
        // 故意放入超过截断上限的值，使截断范围参数变化能体现在结果里
        dataset.put("salary", "eng",
                List.of(10.0, 20.0, 30.0, 40.0, 50.0, 2000.0));
        service = new QueryService(ledger, state, dataset, audit);
        state.grant(CALLER, "salary");
        ledger.topUp(CALLER, 100.0);
    }

    private QueryRequest sumReq(String reqId) {
        return new QueryRequest(reqId, CALLER, "salary", StatisticType.SUM, "eng");
    }

    @Test
    @DisplayName("版本复现: 发布新参数后老结论按旧版本复算，与当初逐位一致")
    void oldConclusionsReplayExactlyAfterNewVersion() {
        // v1: 默认参数 ε=0.1, 门槛5, 截断±1000
        QueryResult old1 = service.execute(sumReq("old-1"));
        QueryResult old2 = service.execute(sumReq("old-2"));
        long oldVersion = old1.version();
        assertEquals(oldVersion, old2.version());
        long seq1 = audit.all().get(0).seq();

        // 发布 v2：单次 ε 调大、门槛调低、截断范围调小
        long v2 = state.publishParams(new QueryParams(0.5, 3, 500.0));
        assertEquals(oldVersion + 1, v2);

        QueryResult fresh = service.execute(sumReq("new-1"));
        assertEquals(v2, fresh.version(), "新查询必须落在新版本");
        assertEquals(0.5, fresh.epsilonConsumed(), 1e-12, "新查询按新 ε 扣预算");
        assertNotEquals(old1.value(), fresh.value(), "新参数（ε/截断）下加噪结果应不同");

        // 老结论按旧版本复算：值必须与当初完全一致（double 逐位）
        ReplayResult replay1 = service.replay(seq1);
        assertEquals(oldVersion, replay1.version());
        assertEquals(old1.batchId(), replay1.batchId());
        assertEquals(old1.value(), replay1.originalValue(), 0.0, "审计原始值");
        assertEquals(old1.value(), replay1.replayedValue(), 0.0,
                "按历史版本重算必须与当初逐位一致");
        log("参数版本复现", "发布v2后复算v1结论",
                String.format("通过: 原值=%.6f 重算=%.6f 完全一致; 新查询走v2 ε=0.5",
                        old1.value(), replay1.replayedValue()));
    }

    @Test
    @DisplayName("版本复现: 新版本下多个新结论也可复现，且新旧互不串台")
    void bothVersionsReplayIndependently() {
        QueryResult oldResult = service.execute(sumReq("r-old"));
        long oldSeq = audit.all().get(0).seq();

        state.publishParams(new QueryParams(0.2, 5, 800.0));
        QueryResult newResult = service.execute(sumReq("r-new"));
        long newSeq = audit.all().get(1).seq();

        assertEquals(oldResult.value(), service.replay(oldSeq).replayedValue(), 0.0);
        assertEquals(newResult.value(), service.replay(newSeq).replayedValue(), 0.0);
        assertEquals(oldResult.version(), service.replay(oldSeq).version());
        assertEquals(newResult.version(), service.replay(newSeq).version());
        assertNotEquals(service.replay(oldSeq).version(), service.replay(newSeq).version());
        log("参数版本复现", "新旧版本各自复现", "通过: v" + oldResult.version() + " 与 v"
                + newResult.version() + " 结论分别可复现");
    }

    @Test
    @DisplayName("版本复现: 历史版本下各调用方授权状态可查回")
    void historicalAuthPerCallerTraceable() {
        state.grant("caller-b", "salary");            // A、B 都授权
        long versionBeforeRevoke = state.currentVersion();
        state.revoke(CALLER, "salary");                // 撤 A

        assertEquals(AuthState.REVOKED, state.authState(CALLER, "salary"));
        assertEquals(AuthState.GRANTED, state.authStateAt(CALLER, "salary", versionBeforeRevoke),
                "撤权前的版本里 A 仍是 GRANTED");
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-b", "salary", state.currentVersion()),
                "当前版本 B 仍 GRANTED");
        log("参数版本复现", "历史版本按调用方授权回放",
                "通过: 撤权版本v" + state.currentVersion()
                        + " 中 A=REVOKED/B=GRANTED，v" + versionBeforeRevoke + " 中 A=GRANTED");
    }

    @Test
    @DisplayName("版本复现: 审计容量裁剪联动快照裁剪，窗口外拒绝(VERSION_NOT_FOUND)、窗口内仍一致")
    void capacityWindowKeepsReplayAlive() {
        // 容量为 2 条审计记录；state/ledger/dataset 均用独立实例，避免与其它用例互相影响
        AuditTrail smallAudit = new AuditTrail(2);
        BudgetLedger localLedger = new BudgetLedger();
        SystemStateService localState = new SystemStateService();
        QueryService svc = new QueryService(localLedger, localState, serviceDataset(), smallAudit);
        localState.grant(CALLER, "salary");
        localLedger.topUp(CALLER, 100.0);

        QueryResult r1 = svc.execute(sumReq("c1"));
        long v1 = r1.version();
        long seq1 = smallAudit.all().get(0).seq();
        svc.execute(sumReq("c2"));
        // 第三条把第一条挤出审计窗口，并联动裁掉其独有快照
        QueryResult r3 = svc.execute(sumReq("c3"));

        assertEquals(2, smallAudit.retainedSize(), "审计只保留最近 2 条");
        assertTrue(localState.oldestRetainedVersion() >= smallAudit.all().get(0).version(),
                "快照裁剪不得裁掉仍保留审计所需的版本");

        QueryRejectedException ex = assertThrows(QueryRejectedException.class,
                () -> svc.replay(seq1));
        assertEquals(RejectionReason.VERSION_NOT_FOUND, ex.getReason(),
                "窗口外的历史结论应明确拒绝，而不是给出错误重算值");

        // 窗口内的结论仍可精确复现
        long seq3 = smallAudit.all().get(1).seq();
        assertEquals(r3.value(), svc.replay(seq3).replayedValue(), 0.0,
                "保留窗口内复现不受容量控制影响");
        assertEquals(v1, r1.version());
        log("参数版本复现", "容量窗口联动裁剪",
                "通过: 容量2, 最老记录v" + smallAudit.all().get(0).version()
                        + "可复现, 更早seq=" + seq1 + " 拒绝 VERSION_NOT_FOUND");
    }

    private Dataset serviceDataset() {
        Dataset ds = new Dataset();
        ds.put("salary", "eng", List.of(10.0, 20.0, 30.0, 40.0, 50.0, 2000.0));
        return ds;
    }
}
