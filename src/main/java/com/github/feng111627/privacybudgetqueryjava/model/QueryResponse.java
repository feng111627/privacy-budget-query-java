package com.github.feng111627.privacybudgetqueryjava.model;

import java.util.List;

/** 查询结论：结果分组、预算扣减、规则版本与可解释说明。 */
public record QueryResponse(
        String requestId,
        String batchId,
        String fingerprint,
        List<GroupValue> groups,
        double epsilonCharged,
        double remainingBudget,
        String budgetModelVersion,
        String authorizationVersion,
        String explanation) {
}
