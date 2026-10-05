package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.auth.AuthorizationPolicy;
import com.github.feng111627.privacybudgetqueryjava.budget.BudgetModel;
import com.github.feng111627.privacybudgetqueryjava.model.MetricType;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRecord;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import com.github.feng111627.privacybudgetqueryjava.service.RejectedQueryException;
import com.github.feng111627.privacybudgetqueryjava.tests.support.PrivacyTestKit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：字段级授权与撤权，以及规则/预算版本的可追溯复现。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RevocationAndVersioningTest {

    private static final Logger log = Logger.getLogger(RevocationAndVersioningTest.class.getName());

    private final PrivacyTestKit kit = new PrivacyTestKit();

    @Test
    @Order(1)
    void unauthorizedAndRevokedFieldsAreRejectedDifferently() {
        kit.register("heidi", "region"); // 只有 region，没有 income
        String batch = "batch-heidi-0";
        log.info("【字段授权】用例1: 未授权 income 做 SUM → FIELD_NOT_AUTHORIZED；对 region 撤权 → AUTHORIZATION_REVOKED");

        RejectedQueryException noAuth = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(sum("heidi", "h-1", batch)));
        assertEquals(RejectionCode.FIELD_NOT_AUTHORIZED, noAuth.code());
        log.info("  未授权字段 code=" + noAuth.code());

        // 授权后的 COUNT 可以成功。
        QueryResponse ok = kit.service.submit(count("heidi", "h-2", batch));
        assertTrue(ok.groups().size() > 1);
        log.info("  region COUNT 成功，fingerprint=" + ok.fingerprint());

        // 字段撤权后，同字段新请求被拒，且原因与“从未授权”可区分。
        kit.service.revokeField("heidi", "region");
        RejectedQueryException revoked = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("heidi", "h-3", batch)));
        assertEquals(RejectionCode.AUTHORIZATION_REVOKED, revoked.code());
        log.info("  撤权后新请求 code=" + revoked.code());
        log.info("【字段授权】结果: 未授权与撤权是两个不同原因，撤权字段不再参与统计 ✔");
    }

    @Test
    @Order(2)
    void callerRevocationStopsAllQueriesAndDuplicateStaysConsistent() {
        String batch = kit.register("ivan", "region", "income");
        log.info("【调用方撤权】用例2: 先成功一次，再整体撤权 → 后续拒绝；原 requestId 重放仍判重复");

        QueryResponse first = kit.service.submit(count("ivan", "i-1", batch));
        kit.service.revokeCaller("ivan");

        RejectedQueryException after = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("ivan", "i-2", batch)));
        assertEquals(RejectionCode.AUTHORIZATION_REVOKED, after.code());
        log.info("  撤权后新请求 code=" + after.code());

        // 撤权不改变历史结论：i-1 重放仍然是“重复提交”，而非按撤权处理。
        RejectedQueryException replay = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("ivan", "i-1", batch)));
        assertEquals(RejectionCode.DUPLICATE_REQUEST, replay.code());
        log.info("  历史 requestId 重放 code=" + replay.code()
                + "（保持首次处理时的结论，不被撤权改写）");

        // 恢复后可以继续查询。
        kit.service.reinstateCaller("ivan");
        QueryResponse again = kit.service.submit(count("ivan", "i-3", batch));
        assertTrue(again.epsilonCharged() > 0);
        log.info("  恢复授权后请求成功，扣减=" + again.epsilonCharged() + "ε");
        log.info("【调用方撤权】结果: 撤权即时生效、历史结论稳定、恢复可用 ✔");
    }

    @Test
    @Order(3)
    void historyRecordsVersionsAndRejectionsAreAuditable() {
        String batch = kit.register("judy", "region"); // 只授权 region，未授权 income
        kit.service.submit(count("judy", "j-1", batch));
        assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(sum("judy", "j-2", batch))); // 未授权 income
        List<QueryRecord> history = kit.service.history("judy");
        log.info("【流水追溯】用例3: 1 次接受 + 1 次拒绝 → 流水均带版本与原因");

        assertEquals(2, history.size());
        assertTrue(history.get(0).accepted());
        assertFalse(history.get(1).accepted());
        assertEquals(RejectionCode.FIELD_NOT_AUTHORIZED, history.get(1).rejectionCode());
        assertEquals("budget-v1", history.get(0).budgetModelVersion());
        history.forEach(r -> log.info(String.format(
                "  request=%s accepted=%s code=%s budgetModel=%s authVersion=%s",
                r.requestId(), r.accepted(), r.rejectionCode(),
                r.budgetModelVersion(), r.authorizationVersion())));
        log.info("【流水追溯】结果: 接受/拒绝都留痕，可按版本审计 ✔");
    }

    @Test
    @Order(4)
    void historicalConclusionReproducibleAfterModelAndRuleChanges() {
        String batch = kit.register("kate", "region", "income");
        log.info("【版本复现】用例4: 先在 budget-v1 下查询并保存结论，随后发布新预算模型与撤权规则，"
                + "历史结论仍能按原版本复现");

        QueryResponse historical = kit.service.submit(count("kate", "k-1", batch));
        double oldNoisy = historical.groups().stream()
                .filter(g -> g.groupKey().equals("north")).findFirst().orElseThrow().noisyValue();
        String oldFp = historical.fingerprint();
        log.info("  历史结论: fingerprint=" + oldFp + "，north noisyCount=" + oldNoisy);

        // 规则变更：发布更贵/更严的新预算模型；并撤权 region。
        kit.budgetRegistry.publish(new BudgetModel(
                "budget-v2", Instant.now(), 2.0d, 8, 0d, 1_000_000d,
                0.95d, 0.25d, 0.50d));
        kit.service.revokeField("kate", "region");

        // 新规则下的新请求必须被撤权拒绝。
        RejectedQueryException blocked = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("kate", "k-2", batch)));
        assertEquals(RejectionCode.AUTHORIZATION_REVOKED, blocked.code());
        log.info("  规则更新后新请求被拒绝 code=" + blocked.code());

        // 历史流水仍指向旧版本；按旧版本号取回的模型参数与旧指纹都保持不变。
        QueryRecord record = kit.ledger.history("kate").stream()
                .filter(r -> r.requestId().equals("k-1")).findFirst().orElseThrow();
        assertEquals("budget-v1", record.budgetModelVersion());
        BudgetModel oldModel = kit.budgetRegistry.version(record.budgetModelVersion());
        AuthorizationPolicy oldAuth =
                kit.authorizationRegistry.version(record.authorizationVersion());
        assertEquals(1.0d, oldModel.totalEpsilon(), 1e-9);
        assertEquals(0.10d, oldModel.countCostEpsilon(), 1e-9);
        assertTrue(oldAuth.authorizedFields().get("kate").contains("region"),
                "历史授权快照中 region 仍处于授权状态");
        assertEquals(oldFp, record.fingerprint());
        assertEquals(oldNoisy,
                record.response().groups().stream()
                        .filter(g -> g.groupKey().equals("north")).findFirst().orElseThrow()
                        .noisyValue(), 1e-12);
        log.info("  按版本取回: totalEpsilon=" + oldModel.totalEpsilon()
                + " countCost=" + oldModel.countCostEpsilon()
                + "，历史响应中的 north noisyCount 仍=" + oldNoisy);
        log.info("【版本复现】结果: 规则/预算更新后，历史结论、参数与指纹仍可按原版本追溯复现 ✔");
    }

    private static QueryRequest count(String caller, String reqId, String batch) {
        return new QueryRequest(caller, reqId, batch, MetricType.COUNT, "region", null);
    }

    private static QueryRequest sum(String caller, String reqId, String batch) {
        return new QueryRequest(caller, reqId, batch, MetricType.SUM, "region", "income");
    }
}
