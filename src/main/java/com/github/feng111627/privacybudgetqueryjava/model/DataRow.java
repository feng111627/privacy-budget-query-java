package com.github.feng111627.privacybudgetqueryjava.model;

import java.util.Map;

/** 一行样本数据：维度字段（用于分组）与度量字段（用于统计）。 */
public record DataRow(Map<String, String> dimensions, Map<String, Double> metrics) {
    public DataRow {
        dimensions = Map.copyOf(dimensions);
        metrics = Map.copyOf(metrics);
    }
}
