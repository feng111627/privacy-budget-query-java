package com.github.feng111627.privacybudgetqueryjava.tests;

import com.github.feng111627.privacybudgetqueryjava.model.GroupValue;
import com.github.feng111627.privacybudgetqueryjava.model.MetricType;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.tests.support.PrivacyTestKit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：
 * - 最小分组样本量保护：west 仅 2 行（< 5），必须被抑制，不返回真实值；
 * - 同一调用方、同一预算批次内相同查询（不同 requestId）必须得到完全一致、
 *   可复现的结论（确定性噪声），且重复查询也会各自消耗预算；
 * - 结果带误差范围与预算说明。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SampleSuppressionAndReproducibilityTest {

    private static final Logger log = Logger.getLogger(
            SampleSuppressionAndReproducibilityTest.class.getName());

    private final PrivacyTestKit kit = new PrivacyTestKit();

    @Test
    @Order(1)
    void smallGroupsSuppressedAndErrorRangeExplained() {
        String batch = kit.register("dora", "region", "income");
        log.info("【样本抑制】用例1: 按 region 做 COUNT，west 仅2行 < 最小样本量5 → 期望 west 被抑制");

        QueryResponse resp = kit.service.submit(count("dora", "d-1", batch));
        GroupValue west = resp.groups().stream()
                .filter(g -> g.groupKey().equals("west")).findFirst().orElseThrow();
        assertTrue(west.suppressed());
        assertEquals(0d, west.noisyValue());
        log.info("  west 分组: suppressed=true, 不返回真实统计值");

        GroupValue north = resp.groups().stream()
                .filter(g -> g.groupKey().equals("north")).findFirst().orElseThrow();
        assertTrue(north.sufficientSamples());
        assertTrue(north.marginOfError() > 0d, "充足分组必须带误差范围");
        log.info(String.format("  north 分组: noisyCount=%.4f 误差范围=±%.4f",
                north.noisyValue(), north.marginOfError()));
        assertNotNull(resp.explanation());
        assertTrue(resp.explanation().contains("suppressed=1"));
        assertTrue(resp.explanation().contains("epsilonCharged"));
        log.info("  说明文本: " + resp.explanation());
        log.info("【样本抑制】结果: 小样本被抑制、大样本返回带误差范围的加噪结果 ✔");
    }

    @Test
    @Order(2)
    void sameQueryInSameBatchIsStableAndReproducible() {
        String batch = kit.register("erin", "region", "income");
        log.info("【可复现】用例2: 同批次内3次相同 COUNT（不同requestId）→ 期望分组值与指纹完全一致");

        QueryResponse r1 = kit.service.submit(count("erin", "e-1", batch));
        QueryResponse r2 = kit.service.submit(count("erin", "e-2", batch));
        QueryResponse r3 = kit.service.submit(count("erin", "e-3", batch));

        assertEquals(r1.fingerprint(), r2.fingerprint());
        assertValuesEqual(r1.groups(), r2.groups());
        assertValuesEqual(r2.groups(), r3.groups());
        log.info("  三次查询指纹=" + r1.fingerprint());
        r1.groups().forEach(g -> log.info(String.format("  group=%-5s noisy=%.6f 三次一致",
                g.groupKey(), g.noisyValue())));
        // 每次都按实际消耗扣预算。
        assertEquals(0.3d, kit.service.snapshot("erin", batch).usedBudget(), 1e-9);
        log.info("【可复现】结果: 相同查询结论逐位一致，且各次均扣减预算 ✔");
    }

    @Test
    @Order(3)
    void differentBatchesAreIndependentAndVersionsRecorded() {
        String b1 = kit.register("frank", "region", "income");
        kit.service.openBatch("frank", "second");
        log.info("【可复现】用例3: 同一调用方两个批次，相同查询 → 每批次内部稳定，批次间噪声独立，版本被锚定");

        QueryResponse r1a = kit.service.submit(count("frank", "f-a1", b1));
        QueryResponse r1b = kit.service.submit(count("frank", "f-a2", b1));
        QueryResponse r2a = kit.service.submit(count("frank", "f-b1", "second"));
        QueryResponse r2b = kit.service.submit(count("frank", "f-b2", "second"));

        // 批次内部完全一致。
        assertValuesEqual(r1a.groups(), r1b.groups());
        assertValuesEqual(r2a.groups(), r2b.groups());
        // 不同批次指纹不同，且至少存在一个分组加噪值不同（噪声按批次独立）。
        assertNotEquals(r1a.fingerprint(), r2a.fingerprint());
        boolean anyDifferent = false;
        for (int i = 0; i < r1a.groups().size(); i++) {
            GroupValue g1 = r1a.groups().get(i);
            GroupValue g2 = r2a.groups().get(i);
            if (!g1.suppressed() && g1.noisyValue() != g2.noisyValue()) {
                anyDifferent = true;
            }
        }
        assertTrue(anyDifferent, "不同批次的噪声应当相互独立");
        assertEquals("budget-v1", r1a.budgetModelVersion());
        assertNotNull(r1a.authorizationVersion());
        log.info("  批次A指纹=" + r1a.fingerprint());
        log.info("  批次B指纹=" + r2a.fingerprint());
        log.info("  预算模型版本=" + r1a.budgetModelVersion()
                + "，授权快照版本=" + r1a.authorizationVersion());
        log.info("【可复现】结果: 批内稳定、批间独立，结论锚定规则版本 ✔");
    }

    private static void assertValuesEqual(List<GroupValue> a, List<GroupValue> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).groupKey(), b.get(i).groupKey());
            assertEquals(a.get(i).noisyValue(), b.get(i).noisyValue(), 1e-12);
            assertEquals(a.get(i).suppressed(), b.get(i).suppressed());
        }
    }

    private static QueryRequest count(String caller, String reqId, String batch) {
        return new QueryRequest(caller, reqId, batch, MetricType.COUNT, "region", null);
    }
}
