package com.github.feng111627.privacybudgetqueryjava.model;

import java.time.Instant;

/** 查询流水：无论接受还是拒绝都留痕，用于审计与历史复现。 */
public record QueryRecord(
        Instant at,
        String callerId,
        String requestId,
        String batchId,
        String fingerprint,
        boolean accepted,
        RejectionCode rejectionCode,
        double epsilonCharged,
        String budgetModelVersion,
        String authorizationVersion,
        QueryResponse response) {
}
