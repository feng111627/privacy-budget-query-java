package com.github.feng111627.privacybudgetqueryjava.ledger;

/**
 * 一次预算预留的句柄。账本在 reserve 时完成原子校验与预算占位，
 * 服务层随后执行查询，并通过 commit 收口（扣减预算、写入流水）。
 */
public record Reservation(
        String callerId,
        String batchId,
        String requestId,
        String fingerprint,
        double epsilon,
        String budgetModelVersion,
        String authorizationVersion,
        long seq) {
}
