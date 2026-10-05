package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
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
            case FIELD_NOT_AUTHORIZED -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(Map.of(
                "rejected", true,
                "reason", ex.getReason().name(),
                "message", ex.getMessage()));
    }
}
