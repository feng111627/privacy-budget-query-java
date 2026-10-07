package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 隐私统计参数（可按版本发布）。
 *
 * @param epsilonPerQuery 单次查询消耗的隐私预算 ε
 * @param minGroupSize    最小分组样本量门槛
 * @param maxValue        单条记录取值截断上限（对称截断到 [-maxValue, maxValue]）
 */
public record QueryParams(double epsilonPerQuery, int minGroupSize, double maxValue) {
}
