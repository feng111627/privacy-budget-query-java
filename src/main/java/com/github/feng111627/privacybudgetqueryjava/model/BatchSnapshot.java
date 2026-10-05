package com.github.feng111627.privacybudgetqueryjava.model;

/** 某调用方某预算批次的账本快照。 */
public record BatchSnapshot(
        String callerId,
        String batchId,
        double totalBudget,
        double usedBudget,
        double remainingBudget,
        String budgetModelVersion,
        boolean open) {
}
