package com.github.feng111627.privacybudgetqueryjava.service;

import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;

/** 查询被拒绝时抛出，携带可区分的拒绝原因码。 */
public class RejectedQueryException extends RuntimeException {

    private final RejectionCode code;

    public RejectedQueryException(RejectionCode code, String message) {
        super(message);
        this.code = code;
    }

    public RejectionCode code() {
        return code;
    }
}
