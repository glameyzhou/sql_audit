package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.exception.IllegalStateTransitionException;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.server.auth.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误出口：业务错误码 + 消息（对齐前端 api/index.js 的错误拦截约定）。
 *
 * <p>业务异常（{@link SqlauditException}）的 HTTP 状态按错误码映射（契约 §0 新增错误码的
 * 语义）：401 = UNAUTHORIZED；403 = 无权限 / 越数据范围 / 工单对象级拒绝；409 = 防自锁 /
 * 重复流程 / 状态冲突 / 用户名冲突；其余 400。请求取值非法（JSON 不可解析 / 枚举存储码
 * 不在合法集合内）一律 400 + {@code INVALID_REQUEST}。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常；错误码取自异常自带的 {@link ErrorCode}，HTTP 状态按错误码映射。 */
    @ExceptionHandler(SqlauditException.class)
    public ResponseEntity<Map<String, String>> business(SqlauditException e) {
        return ResponseEntity.status(statusOf(e.getErrorCode())).body(Map.of(
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

    /** 请求方法不受支持（如已删除的 POST /api/permissions）→ 405，不落 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(),
                "message", String.valueOf(e.getMessage())));
    }

    /**
     * 未映射到任何 handler 的路径（Spring 6.1+ 的 NoResourceFoundException，含尾斜杠不
     * 匹配——Spring 6 起默认尾斜杠不匹配，/api/tickets/ 落不到 GET /api/tickets）→ 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", ErrorCode.INVALID_REQUEST.getCode(),
                "message", "接口不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未捕获的系统异常", e);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", ErrorCode.INTERNAL_ERROR.getCode(),
                "message", "系统内部错误，请稍后重试或联系管理员"));
    }

    /** 错误码 → HTTP 状态（契约 §0 语义）。 */
    private static HttpStatus statusOf(ErrorCode code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, INSTANCE_OUT_OF_SCOPE, TICKET_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case RBAC_LOCKOUT, DUPLICATE_FLOW, ILLEGAL_STATE_TRANSITION,
                 TICKET_STATE_CONFLICT, USERNAME_EXISTS -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
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
