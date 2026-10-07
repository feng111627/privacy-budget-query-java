package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 某调用方对某字段在某版本下的授权状态。
 * NEVER_GRANTED 与 REVOKED 必须可区分。
 */
public enum AuthState {
    /** 从来没有被授权过 */
    NEVER_GRANTED,
    /** 当前已授权 */
    GRANTED,
    /** 曾经授权，之后又被撤权 */
    REVOKED
}
