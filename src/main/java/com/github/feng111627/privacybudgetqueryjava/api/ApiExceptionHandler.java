package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把业务拒绝映射为带可区分原因的 HTTP 响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QueryRejectedException.class)
    public ResponseEntity<Map<String, Object>> onRejected(QueryRejectedException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case BUDGET_EXHAUSTED -> HttpStatus.PAYMENT_REQUIRED;
            case DUPLICATE_REQUEST -> HttpStatus.CONFLICT;
            case INSUFFICIENT_SAMPLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case FIELD_NOT_AUTHORIZED, FIELD_NEVER_GRANTED -> HttpStatus.FORBIDDEN;
            case VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of(
                "rejected", true,
                "reason", ex.getReason().name(),
                "message", ex.getMessage()));
    }

    /** 管理侧非法入参（非法参数版本、空调用方/字段等）映射为 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "rejected", true,
                "reason", RejectionReason.INVALID_PARAMETER.name(),
                "message", ex.getMessage() == null ? "非法参数" : ex.getMessage()));
    }
}
