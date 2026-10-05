package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.model.MetricType;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import com.github.feng111627.privacybudgetqueryjava.service.RejectedQueryException;
import com.github.feng111627.privacybudgetqueryjava.tests.support.PrivacyTestKit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：隐私预算按实际消耗扣减，耗尽后拒绝；不同拒绝原因码可区分。
 * 默认模型总 ε=1.0，COUNT 每次 0.1：前 10 次成功，第 11 次预算耗尽。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BudgetExhaustionTest {

    private static final Logger log = Logger.getLogger(BudgetExhaustionTest.class.getName());

    private final PrivacyTestKit kit = new PrivacyTestKit();

    @Test
    @Order(1)
    void budgetChargedPerQueryAndExhaustionRejected() {
        String batch = kit.register("alice", "region", "income");
        log.info("【预算耗尽】用例1: 总预算1.0ε、COUNT单价0.1ε，连续提交11次 → 期望前10成功、第11次BUDGET_EXHAUSTED");

        for (int i = 1; i <= 10; i++) {
            QueryResponse resp = kit.service.submit(
                    count("alice", "r-exhaust-" + i, batch));
            assertEquals(0.10d, resp.epsilonCharged(), 1e-9,
                    "第" + i + "次应扣减0.1ε");
            assertEquals(Math.round((1.0d - 0.10d * i) * 1e6) / 1e6,
                    resp.remainingBudget(), 1e-6);
            log.info(String.format("  第%02d次: accepted, 扣减=%.2fε, 剩余=%.2fε",
                    i, resp.epsilonCharged(), resp.remainingBudget()));
        }

        RejectedQueryException ex = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("alice", "r-exhaust-11", batch)));
        assertEquals(RejectionCode.BUDGET_EXHAUSTED, ex.code());
        log.info("  第11次: rejected code=" + ex.code() + " -> " + ex.getMessage());

        // 预算绝不能被扣成负数。
        assertEquals(0.0d, kit.service.snapshot("alice", batch).remainingBudget(), 1e-9);
        assertEquals(1.0d, kit.service.snapshot("alice", batch).usedBudget(), 1e-9);
        log.info("【预算耗尽】结果: 剩余预算=0.0ε（非负），已用=1.0ε，结论与原因码一致 ✔");
    }

    @Test
    @Order(2)
    void differentRejectionReasonsAreDistinguishable() {
        String batch = kit.register("bob", "region", "income"); // batch-bob-0
        log.info("【拒绝原因区分】用例2: 预算耗尽 vs 参数非法 vs 未授权 vs 重复 → 期望四个不同code");

        // 用 SUM（0.2ε）5 次耗尽 1.0ε。
        for (int i = 1; i <= 5; i++) {
            kit.service.submit(sum("bob", "b-sum-" + i, batch));
        }
        RejectedQueryException exhausted = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(sum("bob", "b-sum-6", batch)));
        log.info("  耗尽拒绝 code=" + exhausted.code());

        RejectedQueryException invalid = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(new QueryRequest("bob", "bad-1", batch,
                        MetricType.SUM, "region", null)));
        log.info("  参数非法 code=" + invalid.code());

        RejectedQueryException unauthorized = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(count("bob-not-exist", "x-1", "no-such-batch")));
        log.info("  调用方不存在 code=" + unauthorized.code());

        RejectedQueryException dup = assertThrows(RejectedQueryException.class,
                () -> kit.service.submit(sum("bob", "b-sum-1", batch)));
        log.info("  重复提交 code=" + dup.code());

        assertEquals(RejectionCode.BUDGET_EXHAUSTED, exhausted.code());
        assertEquals(RejectionCode.INVALID_PARAMETER, invalid.code());
        assertEquals(RejectionCode.CALLER_NOT_FOUND, unauthorized.code());
        assertEquals(RejectionCode.DUPLICATE_REQUEST, dup.code());
        assertTrue(exhausted.code() != invalid.code()
                && invalid.code() != unauthorized.code()
                && unauthorized.code() != dup.code());
        assertNotNull(exhausted.getMessage());
        log.info("【拒绝原因区分】结果: 四类拒绝原因码两两不同，调用方可据此分支处理 ✔");
    }

    private static QueryRequest count(String caller, String reqId, String batch) {
        return new QueryRequest(caller, reqId, batch, MetricType.COUNT, "region", null);
    }

    private static QueryRequest sum(String caller, String reqId, String batch) {
        return new QueryRequest(caller, reqId, batch, MetricType.SUM, "region", "income");
    }
}
