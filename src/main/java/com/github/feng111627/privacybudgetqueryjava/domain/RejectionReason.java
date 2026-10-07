package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 查询被拒绝的原因，不同原因必须可区分。
 */
public enum RejectionReason {
    /** 隐私预算不足 */
    BUDGET_EXHAUSTED,
    /** 请求参数非法 */
    INVALID_PARAMETER,
    /** 重复提交（幂等冲突） */
    DUPLICATE_REQUEST,
    /** 分组样本量不足，触发最小分组保护 */
    INSUFFICIENT_SAMPLE,
    /** 该调用方对该字段从未授权过（与“授权后又被撤掉”严格区分） */
    FIELD_NEVER_AUTHORIZED,
    /** 该调用方曾被授权该字段，但授权状态已被撤掉 */
    FIELD_AUTHORIZATION_REVOKED
}
