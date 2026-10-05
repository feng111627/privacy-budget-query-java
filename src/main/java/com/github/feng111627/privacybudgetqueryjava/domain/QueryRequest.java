package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 一次统计查询请求。
 *
 * @param requestId 调用方提供的幂等键，同一调用方同一 requestId 视为同一请求
 * @param callerId  调用方标识
 * @param field     目标统计字段（需已授权）
 * @param statistic 统计类型
 * @param groupKey  分组键（按该维度过滤出一个分组），为空表示全量
 */
public record QueryRequest(String requestId,
                           String callerId,
                           String field,
                           StatisticType statistic,
                           String groupKey) {
}
