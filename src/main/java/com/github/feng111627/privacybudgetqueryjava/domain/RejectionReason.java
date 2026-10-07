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
    /** 字段曾经授权但之后被撤权（与从未授权相区分） */
    FIELD_NOT_AUTHORIZED,
    /** 该调用方对该字段从来没有被授权过 */
    FIELD_NEVER_GRANTED,
    /** 指定的历史版本已超出保留窗口，无法复现 */
    VERSION_NOT_FOUND
}
