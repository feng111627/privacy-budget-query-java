package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.StatisticType;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：账本幂等缓存与审计日志的容量控制。
 */
class CapacityControlTest {

    private static final QueryResult DUMMY = new QueryResult(0, 0, 0.1, 0, 1, 1, 0, "dummy");

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @Test
    @DisplayName("容量控制: 幂等缓存有界，超出容量淘汰最旧 requestId 且不影响余额")
    void idempotencyCacheBounded() {
        int cap = 100;
        BudgetLedger ledger = new BudgetLedger(cap);
        ledger.topUp("c", 1000.0); // 预算充足，保证全部成功扣减

        for (int i = 0; i < cap + 50; i++) {
            assertTrue(ledger.tryDeduct("c", "req-" + i, 0.1, DUMMY));
        }
        assertEquals(cap, ledger.cachedResultCount("c"), "缓存条数不得超过容量");

        // 最旧的 50 个 requestId 已淘汰，重新提交不再被幂等拦截
        assertTrue(ledger.tryDeduct("c", "req-0", 0.1, DUMMY), "最旧记录被淘汰后可再次提交");
        // 最新的 requestId 仍受幂等保护
        assertFalse(ledger.tryDeduct("c", "req-" + (cap + 49), 0.1, DUMMY),
                "最新记录仍应命中幂等，不能重复扣减");

        // 扣减始终精确：成功 150 + 重放 1 = 151 次，每次 0.1
        assertEquals(1000.0 - 151 * 0.1, ledger.balanceOf("c"), 1e-9, "淘汰只动缓存，不影响余额记账");
        assertTrue(ledger.balanceOf("c") >= 0, "余额不得为负");
        log("容量控制", "幂等缓存有界LRU(cap=100)",
                "通过: 150次后缓存=100, 最旧可重提, 最新仍幂等, 余额=" + ledger.balanceOf("c"));
    }

    @Test
    @DisplayName("容量控制: 审计环形窗口有界，且窗口内历史仍可完整复现")
    void auditRingBoundedAndStillReproducible() {
        int cap = 50;
        AuditTrail audit = new AuditTrail(cap);
        for (int i = 1; i <= cap + 30; i++) {
            audit.next("caller", "req-" + i, "field", StatisticType.SUM, "g", 6,
                    i, 1, 0.1, 100.0 + i, 1.0, "OK");
        }
        assertEquals(cap, audit.size(), "审计保留条数不得超过容量");
        assertEquals(cap, audit.all().size());

        // 窗口保留最近 50 条（req-31 .. req-80），序号连续、内容未损坏
        var all = audit.all();
        assertEquals(31L, all.get(0).seq());
        assertEquals(80L, all.get(cap - 1).seq());
        assertEquals(180.0, all.get(cap - 1).value(), 1e-12);
        // 窗口内每条都仍携带复现所需的版本/批次/查询内容
        AuditTrail.Entry e = all.get(0);
        assertEquals(31L, e.policyVersion());
        assertEquals(1L, e.batchId());
        assertEquals(StatisticType.SUM, e.statistic());
        log("容量控制", "审计环形窗口有界(cap=50)",
                "通过: 80条后保留50条(req-31..req-80), 窗口内版本/批次/内容完整可复现");
    }
}
