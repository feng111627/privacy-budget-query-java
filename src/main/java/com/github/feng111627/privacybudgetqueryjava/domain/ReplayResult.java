package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 按历史版本重放历史查询的结果。
 *
 * @param version        重放所用的版本
 * @param batchId        原查询所在预算批次
 * @param authState      该调用方在该历史版本下对字段的授权三态
 * @param originalValue  当初审计记录的加噪结论
 * @param replayedValue  按历史版本参数重新计算出的加噪结论
 * @param sampleSize     样本量
 */
public record ReplayResult(long version,
                           long batchId,
                           AuthState authState,
                           double originalValue,
                           double replayedValue,
                           int sampleSize) {
}
