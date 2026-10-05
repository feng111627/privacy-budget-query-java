package com.github.feng111627.privacybudgetqueryjava.model;

/**
 * 单个分组的统计结果。noisyValue 为加噪后的统计值，marginOfError 为误差范围半宽，
 * suppressed=true 表示样本量不足被抑制，不直接返回真实结果。
 */
public record GroupValue(
        String groupKey,
        double noisyValue,
        double marginOfError,
        boolean sufficientSamples,
        boolean suppressed) {
}
