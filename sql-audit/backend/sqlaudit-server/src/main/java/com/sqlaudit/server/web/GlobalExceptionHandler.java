package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.server.auth.AuthenticationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误出口：业务错误码 + 消息（对齐前端 api/index.js 的错误拦截约定）。
 *
 * <p>请求取值非法（请求体 JSON 不可解析 / 枚举存储码不在合法集合内 / 请求参数无法绑定）
 * 一律 400 + {@code INVALID_REQUEST}，不再落到兜底的 500——拼错的状态、方言等在入口
 * 即刻暴露。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（ERROR_RULE_BLOCKED / IMPACT_ASSESSMENT_INCOMPLETE / SELF_APPROVAL_FORBIDDEN 等）；错误码取自异常自带的 {@link ErrorCode}。 */
    @ExceptionHandler(SqlauditException.class)
    public ResponseEntity<Map<String, String>> business(SqlauditException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", e.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    /** 登录认证失败（用户名/密码错误、账号停用）→ 401。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> unauthorized(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "code", ErrorCode.UNAUTHORIZED.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    /** 非法状态迁移（工单不在可操作状态 / 并发冲突）。 */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> transition(IllegalStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    /**
     * 请求体不可读（JSON 格式错误 / 请求体里的枚举存储码不在合法集合内）→ 400。
     *
     * <p>消息取最底层 cause 的消息：枚举取值非法时即 {@code CodedEnum.fromCode} 抛出的
     * {@code 未知的 X 取值: "...", 合法值: [...]}，原样返回给前端。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return badRequest(mostSpecificMessage(e, "请求体无法解析"));
    }

    /**
     * 请求参数无法转换为目标类型（{@code @RequestParam} / {@code @PathVariable} 里的枚举
     * 存储码不在合法集合内，即 {@link org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}
     * 的父类）→ 400。
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(TypeMismatchException e) {
        return badRequest(e.getMessage() == null ? "参数取值非法" : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of(
                "code", ErrorCode.INTERNAL_ERROR.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(),
                "message", message));
    }

    /** 沿 cause 链取最深层（最具体）的非空消息；都没有则用兜底文案。 */
    private static String mostSpecificMessage(Throwable e, String fallback) {
        String message = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                message = t.getMessage();
            }
        }
        return message == null ? fallback : message;
    }
}
