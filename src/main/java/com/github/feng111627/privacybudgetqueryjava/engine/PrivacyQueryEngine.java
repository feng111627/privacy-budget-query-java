package com.github.feng111627.privacybudgetqueryjava.engine;

import com.github.feng111627.privacybudgetqueryjava.auth.AuthorizationPolicy;
import com.github.feng111627.privacybudgetqueryjava.budget.BudgetModel;
import com.github.feng111627.privacybudgetqueryjava.data.Dataset;
import com.github.feng111627.privacybudgetqueryjava.model.DataRow;
import com.github.feng111627.privacybudgetqueryjava.model.GroupValue;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 隐私统计查询引擎（拉普拉斯机制）。
 *
 * - COUNT 敏感度 Δ=1；SUM 敏感度 Δ=clampMax-clampMin（度量值逐行先裁剪）；
 * - 每个分组加入 Lap(Δ/ε) 噪声；
 * - 样本量低于 BudgetModel.minGroupSize() 的分组被抑制，只返回“样本不足”标记；
 * - 噪声种子由 (指纹, 组键, 模型版本) 派生，保证同调用方、同预算批次、相同查询
 *   产出完全一致的结论（稳定、可复现）；
 * - 误差范围按置信水平对应的 ±k·b 报告。
 */
@Component
public class PrivacyQueryEngine {

    private final Dataset dataset;
    private final NoiseSampler sampler;

    public PrivacyQueryEngine(Dataset dataset, NoiseSampler sampler) {
        this.dataset = dataset;
        this.sampler = sampler;
    }

    public QueryResponse run(QueryRequest request, double epsilonCharged, double remainingBudget,
                             BudgetModel model, AuthorizationPolicy policy, String fingerprint) {
        TreeMap<String, List<DataRow>> grouped = group(request);
        double sensitivity = sensitivity(request, model);
        double scale = sensitivity / epsilonCharged;
        double marginFactor = confidenceFactor(model.confidenceLevel());

        List<GroupValue> groups = new ArrayList<>();
        int suppressedCount = 0;
        for (Map.Entry<String, List<DataRow>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            List<DataRow> rows = entry.getValue();
            boolean sufficient = rows.size() >= model.minGroupSize();
            if (!sufficient) {
                suppressedCount++;
                groups.add(new GroupValue(key, 0d, 0d, false, true));
                continue;
            }
            double trueValue = aggregate(request, rows, model);
            long seed = seedFor(fingerprint, key, model.version());
            double noise = sampler.nextLaplace(scale, seed);
            double noisy = clamp(trueValue + noise, model);
            double margin = marginFactor * scale;
            groups.add(new GroupValue(key, round(noisy), round(margin), true, false));
        }

        String explanation = String.format(
                "metric=%s dimension=%s epsilonCharged=%.6f remainingBudget=%.6f "
                        + "laplaceScale=%.6f marginOfError=±%.6f (confidence=%.0f%%) "
                        + "sensitivity=%.1f minGroupSize=%d groups=%d suppressed=%d "
                        + "budgetModel=%s authorization=%s fingerprint=%s",
                request.metric(), request.dimensionField(), epsilonCharged, remainingBudget,
                scale, marginFactor * scale, model.confidenceLevel() * 100d,
                sensitivity, model.minGroupSize(), groups.size(), suppressedCount,
                model.version(), policy.version(), fingerprint);

        return new QueryResponse(request.requestId(), request.batchId(), fingerprint,
                List.copyOf(groups), epsilonCharged, round(remainingBudget),
                model.version(), policy.version(), explanation);
    }

    /** 按维度字段分组，使用 TreeMap 保证组键顺序确定。 */
    private TreeMap<String, List<DataRow>> group(QueryRequest request) {
        TreeMap<String, List<DataRow>> grouped = new TreeMap<>();
        for (DataRow row : dataset.rows()) {
            String key = row.dimensions().get(request.dimensionField());
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /** 真实分组聚合值（仅在样本量达标时才会被使用）。 */
    private double aggregate(QueryRequest request, List<DataRow> rows, BudgetModel model) {
        if (request.metric() == com.github.feng111627.privacybudgetqueryjava.model.MetricType.COUNT) {
            return rows.size();
        }
        double sum = 0d;
        for (DataRow row : rows) {
            Double v = row.metrics().get(request.metricField());
            if (v != null) {
                sum += clamp(v, model);
            }
        }
        return sum;
    }

    private double sensitivity(QueryRequest request, BudgetModel model) {
        return switch (request.metric()) {
            case COUNT -> 1d;
            case SUM -> model.clampMax() - model.clampMin();
        };
    }

    /**
     * 确定性噪声种子：指纹已包含调用方、批次、查询要素与预算版本，
     * 再混入组键与模型版本，确保不同分组/不同模型的噪声彼此独立且可复现。
     */
    private static long seedFor(String fingerprint, String groupKey, String modelVersion) {
        long h = 0xcbf29ce484222325L;
        for (char c : (fingerprint + "|" + groupKey + "|" + modelVersion).toCharArray()) {
            h ^= c;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** 拉普拉斯分布在给定置信水平下的半宽系数：k = -ln(1-p)（单侧）。 */
    private static double confidenceFactor(double confidenceLevel) {
        if (confidenceLevel <= 0d || confidenceLevel >= 1d) {
            throw new IllegalArgumentException("confidenceLevel must be within (0,1)");
        }
        return -Math.log(1d - confidenceLevel);
    }

    private static double clamp(double value, BudgetModel model) {
        return Math.max(model.clampMin(), Math.min(model.clampMax(), value));
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
