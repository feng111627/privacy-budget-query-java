package com.github.feng111627.privacybudgetqueryjava.model;

/** 查询被拒绝的原因，不同原因必须可区分。 */
public enum RejectionCode {
    INVALID_PARAMETER,
    CALLER_NOT_FOUND,
    BATCH_NOT_FOUND,
    FIELD_NOT_AUTHORIZED,
    AUTHORIZATION_REVOKED,
    BUDGET_EXHAUSTED,
    DUPLICATE_REQUEST
}
