package com.github.feng111627.privacybudgetqueryjava.web;

import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import com.github.feng111627.privacybudgetqueryjava.service.RejectedQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把拒绝原因码映射为可区分的 HTTP 响应：
 * - 参数非法：400；
 * - 调用方/批次不存在：404；
 * - 未授权/撤权/预算耗尽/重复提交：409（语义冲突）。
 * 响应体始终携带稳定的 code 字段，调用方据此精确区分原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RejectedQueryException.class)
    public ResponseEntity<Map<String, String>> handle(RejectedQueryException ex) {
        HttpStatus status = switch (ex.code()) {
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case CALLER_NOT_FOUND, BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FIELD_NOT_AUTHORIZED, AUTHORIZATION_REVOKED,
                 BUDGET_EXHAUSTED, DUPLICATE_REQUEST -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(Map.of(
                        "code", ex.code().name(),
                        "reason", reason(ex.code()),
                        "message", ex.getMessage()));
    }

    private static String reason(RejectionCode code) {
        return switch (code) {
            case INVALID_PARAMETER -> "请求参数非法或引用了不存在的字段";
            case CALLER_NOT_FOUND -> "调用方未登记";
            case BATCH_NOT_FOUND -> "预算批次不存在";
            case FIELD_NOT_AUTHORIZED -> "字段未授权";
            case AUTHORIZATION_REVOKED -> "授权已被撤销";
            case BUDGET_EXHAUSTED -> "隐私预算已耗尽";
            case DUPLICATE_REQUEST -> "重复提交的请求";
        };
    }
}
