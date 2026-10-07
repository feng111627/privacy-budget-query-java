package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.VersionSnapshot;
import com.github.feng111627.privacybudgetqueryjava.versioning.SystemStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务域：按调用方隔离的授权三态与参数版本发布/追溯。
 */
class SystemStateServiceTest {

    private static void log(String area, String caseName, String result) {
        System.out.printf("[单测覆盖] 业务=%s | 用例=%s | 结果=%s%n", area, caseName, result);
    }

    @Test
    @DisplayName("授权: 撤权一个调用方不影响其他调用方")
    void revocationIsolatedByCaller() {
        SystemStateService state = new SystemStateService();
        long va = state.grant("caller-a", "region");
        long vb = state.grant("caller-b", "region");

        long vr = state.revoke("caller-a", "region");

        assertEquals(AuthState.REVOKED, state.authState("caller-a", "region"), "A 已撤权");
        assertEquals(AuthState.GRANTED, state.authState("caller-b", "region"), "B 不受牵连，仍授权");
        // 历史版本也按调用方隔离
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-a", "region", va));
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-b", "region", vb));
        assertEquals(AuthState.REVOKED, state.authStateAt("caller-a", "region", vr));
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-b", "region", vr),
                "撤权 A 的新版本里 B 依旧是 GRANTED");
        log("按调用方授权", "撤权隔离(A撤权/B仍授权)",
                "通过: 当前 A=REVOKED, B=GRANTED; 历史版本各自可查");
    }

    @Test
    @DisplayName("授权: 从未授权与授权后撤权是两种可区分状态")
    void neverGrantedDistinctFromRevoked() {
        SystemStateService state = new SystemStateService();
        state.grant("caller-a", "region");
        state.revoke("caller-a", "region");

        assertEquals(AuthState.REVOKED, state.authState("caller-a", "region"), "授权后撤权");
        assertEquals(AuthState.NEVER_GRANTED, state.authState("caller-b", "region"), "从未授权");
        assertEquals(AuthState.NEVER_GRANTED, state.authState("caller-a", "other-field"),
                "同一调用方对别的字段仍是从未授权");
        log("按调用方授权", "三态区分(从未授权/已撤权)",
                "通过: A.region=REVOKED, B.region=NEVER_GRANTED");
    }

    @Test
    @DisplayName("授权: 变更产生全局单调递增版本号，快照不可变")
    void versionsMonotonicAndSnapshotsImmutable() {
        SystemStateService state = new SystemStateService();
        long v1 = state.grant("caller-a", "f1");
        long v2 = state.publishParams(new QueryParams(0.2, 3, 500.0));
        long v3 = state.grant("caller-b", "f2");
        long v4 = state.revoke("caller-a", "f1");

        assertTrue(v1 < v2 && v2 < v3 && v3 < v4, "授权与参数发布共用单调版本号");
        assertEquals(v4, state.currentVersion());

        VersionSnapshot old = state.snapshotAt(v2);
        assertEquals(0.2, old.params().epsilonPerQuery(), 1e-12);
        assertEquals(3, old.params().minGroupSize());
        assertEquals(500.0, old.params().maxValue(), 1e-12);
        // v2 之后的授权变更不得回写进旧快照
        assertEquals(AuthState.NEVER_GRANTED,
                old.authByCaller().getOrDefault("caller-b", java.util.Map.of())
                        .getOrDefault("f2", AuthState.NEVER_GRANTED),
                "旧快照不可变");
        log("按调用方授权", "版本单调与快照不可变",
                "通过: v" + v1 + "<v" + v2 + "<v" + v3 + "<v" + v4 + ", v2参数/授权冻结");
    }

    @Test
    @DisplayName("授权: 可查回历史版本参数与该版本下各调用方授权状态")
    void historicalSnapshotFullyTraceable() {
        SystemStateService state = new SystemStateService();
        state.grant("caller-a", "region"); // v1
        long vp = state.publishParams(new QueryParams(0.3, 8, 200.0)); // v2
        state.grant("caller-b", "region"); // v3

        VersionSnapshot atV2 = state.snapshotAt(vp);
        assertEquals(0.3, atV2.params().epsilonPerQuery(), 1e-12);
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-a", "region", vp));
        assertEquals(AuthState.NEVER_GRANTED, state.authStateAt("caller-b", "region", vp),
                "v2 时 B 还未授权");
        assertEquals(AuthState.GRANTED, state.authState("caller-b", "region"), "当前版本 B 已授权");
        log("参数版本", "历史版本完整追溯",
                "通过: v2 参数 ε=0.3/门槛8/截断200, A=GRANTED,B=NEVER_GRANTED");
    }

    @Test
    @DisplayName("授权: 默认参数与 v0 快照存在，非法参数发布被拒绝")
    void defaultsAndValidation() {
        SystemStateService state = new SystemStateService();
        assertEquals(0L, state.currentVersion());
        assertEquals(SystemStateService.DEFAULT_PARAMS, state.current().params());

        assertThrows(IllegalArgumentException.class,
                () -> state.publishParams(new QueryParams(0, 5, 1000)));
        assertThrows(IllegalArgumentException.class,
                () -> state.publishParams(new QueryParams(0.1, 0, 1000)));
        assertThrows(IllegalArgumentException.class,
                () -> state.grant(" ", "f"));
        assertEquals(0L, state.currentVersion(), "非法发布不得推进版本");
        log("参数版本", "默认值与非法参数拒绝", "通过: v0默认参数保留, 非法发布不产生版本");
    }

    @Test
    @DisplayName("授权: 容量裁剪后旧版本取不回，窗口内版本不受影响")
    void pruningKeepsWindow() {
        SystemStateService state = new SystemStateService();
        long v1 = state.grant("caller-a", "f");
        long v2 = state.grant("caller-a", "g");
        state.pruneBefore(v2);

        assertEquals(null, state.snapshotAt(v1), "v1 应被裁掉");
        assertEquals(v2, state.snapshotAt(v2).version(), "v2 仍在窗口内");
        assertEquals(v2, state.oldestRetainedVersion());
        assertEquals(AuthState.NEVER_GRANTED, state.authStateAt("caller-a", "f", v1),
                "已裁剪版本查授权回落到 NEVER_GRANTED");
        log("参数版本", "快照容量裁剪", "通过: v1已裁, v2保留, 最早可复现版本=v" + v2);
    }

    @Test
    @DisplayName("授权: 重新授权后状态从 REVOKED 变为 GRANTED")
    void grantAfterRevoke() {
        SystemStateService state = new SystemStateService();
        state.grant("caller-a", "region");
        state.revoke("caller-a", "region");
        long v3 = state.grant("caller-a", "region");
        assertEquals(AuthState.GRANTED, state.authStateAt("caller-a", "region", v3));
        assertEquals(AuthState.REVOKED, state.authStateAt("caller-a", "region", v3 - 1));
        assertNotEquals(AuthState.NEVER_GRANTED, state.authState("caller-a", "region"),
                "重新授权后不得再被判为从未授权");
        log("按调用方授权", "撤权后重新授权", "通过: REVOKED -> GRANTED, 历史 REVOKED 保留");
    }
}
