package com.github.feng111627.privacybudgetqueryjava.budget;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算模型版本注册表：发布新版本后，所有历史版本仍然保留并可按版本号取回，
 * 从而保证历史查询结论可以按当时生效的模型版本复现。
 */
@Component
public class BudgetRegistry {

    private final Map<String, BudgetModel> versions = new LinkedHashMap<>();

    public BudgetRegistry() {
        // 内置 v1：每批次总 ε=1.0，分组最小样本量=5，输出裁剪到 [0, 1_000_000]，误差范围按 95% 置信水平报告。
        publish(new BudgetModel("budget-v1", Instant.parse("2026-01-01T00:00:00Z"),
                1.0d, 5, 0.0d, 1_000_000.0d, 0.95d, 0.10d, 0.20d));
    }

    /** 发布（或覆盖）一个版本，并立即成为当前生效版本。 */
    public synchronized void publish(BudgetModel model) {
        if (model == null || model.version() == null || model.version().isBlank()) {
            throw new IllegalArgumentException("budget model and its version are required");
        }
        versions.put(model.version(), model);
    }

    /** 当前生效版本：最近一次发布的模型。 */
    public synchronized BudgetModel current() {
        BudgetModel last = null;
        for (BudgetModel model : versions.values()) {
            last = model;
        }
        return last;
    }

    /** 按版本号取回历史模型；不存在时返回 empty。 */
    public synchronized Optional<BudgetModel> find(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    /** 按版本号取回历史模型；不存在时抛异常。 */
    public synchronized BudgetModel version(String version) {
        BudgetModel model = versions.get(version);
        if (model == null) {
            throw new IllegalArgumentException("unknown budget model version: " + version);
        }
        return model;
    }

    public synchronized Map<String, BudgetModel> all() {
        return Map.copyOf(versions);
    }
}
