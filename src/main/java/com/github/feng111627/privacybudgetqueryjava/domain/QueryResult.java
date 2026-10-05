package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 统计查询结论。
 *
 * @param value           加噪后的统计值
 * @param errorMargin     可解释的误差范围（基于噪声尺度）
 * @param epsilonConsumed 本次查询实际消耗的隐私预算
 * @param budgetRemaining 扣减后调用方剩余预算
 * @param batchId         预算批次号（同批次同查询结论可复现）
 * @param policyVersion   查询时生效的授权规则版本，用于历史结论追溯
 * @param sampleSize      参与统计的样本量
 * @param explanation     人类可读的预算消耗与误差说明
 */
public record QueryResult(double value,
                          double errorMargin,
                          double epsilonConsumed,
                          double budgetRemaining,
                          long batchId,
                          long policyVersion,
                          int sampleSize,
                          String explanation) {
}
