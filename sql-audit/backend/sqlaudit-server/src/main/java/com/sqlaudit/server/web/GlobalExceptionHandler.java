package com.sqlaudit.server.web;

import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误出口：业务错误码 + 消息（对齐前端 api/index.js 的错误拦截约定）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（ERROR_RULE_BLOCKED / IMPACT_ASSESSMENT_INCOMPLETE / SELF_APPROVAL_FORBIDDEN 等）。 */
    @ExceptionHandler(SqlauditException.class)
    public ResponseEntity<Map<String, String>> business(SqlauditException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", e.getCode() == null ? "BUSINESS_ERROR" : e.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    /** 非法状态迁移（工单不在可操作状态 / 并发冲突）。 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> transition(IllegalStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "ILLEGAL_STATE_TRANSITION",
                "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", String.valueOf(e.getMessage())));
    }
}
