package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.GrantState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：按调用方隔离的字段授权与版本化规则（原有行为迁移，无回归）。
 */
class FieldPolicyServiceTest {

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @Test
    @DisplayName("授权: 按调用方授权/撤权产生单调递增的版本号")
    void versionsIncreaseMonotonically() {
        FieldPolicyService policy = new FieldPolicyService();
        long v1 = policy.grant("caller-a", "salary");
        long v2 = policy.grant("caller-b", "age");
        long v3 = policy.revoke("caller-a", "salary");

        assertTrue(v1 < v2 && v2 < v3, "版本号必须单调递增");
        assertEquals(v3, policy.currentVersion());
        log("字段授权", "版本单调递增", "通过: v" + v1 + " < v" + v2 + " < v" + v3);
    }

    @Test
    @DisplayName("授权: 历史版本状态可追溯，规则更新不影响复现")
    void historyVersionTraceable() {
        FieldPolicyService policy = new FieldPolicyService();
        long v1 = policy.grant("caller-a", "salary");
        long v2 = policy.revoke("caller-a", "salary");
        long v3 = policy.grant("caller-a", "salary");

        assertTrue(policy.isAuthorizedAt("caller-a", "salary", v1), "v1 时应为授权");
        assertFalse(policy.isAuthorizedAt("caller-a", "salary", v2), "v2 时应为撤权");
        assertTrue(policy.isAuthorizedAt("caller-a", "salary", v3), "v3 时应为重新授权");
        assertTrue(policy.isAuthorized("caller-a", "salary"));
        assertEquals(3, policy.history().size(), "每次状态跃迁都应留有历史记录");
        log("字段授权", "历史版本追溯", "通过: v1授权/v2撤权/v3再授权均可重放");
    }

    @Test
    @DisplayName("授权: 从未授权的调用方+字段默认 NEVER")
    void unknownFieldNotAuthorized() {
        FieldPolicyService policy = new FieldPolicyService();
        assertEquals(GrantState.NEVER, policy.grantState("caller-x", "unknown"));
        assertFalse(policy.isAuthorized("caller-x", "unknown"));
        assertEquals(GrantState.NEVER, policy.grantStateAt("caller-x", "unknown", 0));
        log("字段授权", "从未授权默认拒绝", "通过");
    }
}
