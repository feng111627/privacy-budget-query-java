package com.github.feng111627.privacybudgetqueryjava.domain;

/**
 * 查询被拒绝时抛出，携带可区分的拒绝原因。
 */
public class QueryRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public QueryRejectedException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
