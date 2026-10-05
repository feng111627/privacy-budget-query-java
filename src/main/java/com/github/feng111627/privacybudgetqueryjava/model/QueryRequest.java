package com.github.feng111627.privacybudgetqueryjava.model;

/**
 * 一次统计查询请求。
 * requestId 由调用方提供，用于幂等去重；batchId 标识预算批次。
 */
public record QueryRequest(
        String callerId,
        String requestId,
        String batchId,
        MetricType metric,
        String dimensionField,
        String metricField) {
}
