package com.github.feng111627.privacybudgetqueryjava.budget;

import java.time.Instant;

/**
 * 预算模型的一个不可变版本。
 * totalEpsilon: 每个预算批次的总 ε；
 * minGroupSize: 分组最小样本量（低于该值的分组被抑制）；
 * clampMin/clampMax: 加噪后输出的裁剪上下界；
 * confidenceLevel: 误差范围所对应的置信水平；
 * countCostEpsilon/sumCostEpsilon: 每次 COUNT/SUM 查询实际扣减的 ε。
 */
public record BudgetModel(
        String version,
        Instant effectiveAt,
        double totalEpsilon,
        int minGroupSize,
        double clampMin,
        double clampMax,
        double confidenceLevel,
        double countCostEpsilon,
        double sumCostEpsilon) {
}
